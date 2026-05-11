# Cloudflare Family DNS — Network Engine Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace generic upstream DNS with Cloudflare Family (1.1.1.3/1.0.0.3), add LRU cache + socket protection + resilience, and expose a PIN-gated UI toggle.

**Architecture:** Approach A — CF Family IPs declared in VPN builder; packet loop intercepts all UDP/53 regardless of destination IP so local filtering continues unchanged. Protected DatagramSocket forwards allowed queries to CF Family. LRU cache (100 entries) avoids redundant upstream calls. `FAKE_DNS_IP` removed.

**Tech Stack:** Kotlin, Android VpnService, DatagramSocket, LinkedHashMap LRU, Jetpack Compose, SharedPreferences, JUnit 4

---

## Files

| File | Change |
|------|--------|
| `android-app/build.gradle.kts` | Add JUnit4 testImplementation |
| `android-app/src/main/kotlin/com/orquestrador/ui/theme/Color.kt` | Add AccentCloudflare |
| `android-app/src/main/kotlin/com/orquestrador/vpn/VpnPreferences.kt` | Add CF Family pref key |
| `android-app/src/main/kotlin/com/orquestrador/vpn/DnsResolver.kt` | Full refactor |
| `android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorVpnService.kt` | Builder + cache + protect |
| `android-app/src/main/kotlin/com/orquestrador/vpn/VpnGuardWorker.kt` | Include CF extra on restart |
| `android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorViewModel.kt` | New toggle + state |
| `android-app/src/main/kotlin/com/orquestrador/ui/OrquestradorScreen.kt` | New Cloudflare card |
| `android-app/src/test/kotlin/com/orquestrador/vpn/DnsResolverTest.kt` | New — LRU + protect tests |

---

## Task 1: Foundation — test dependency and color

**Files:**
- Modify: `android-app/build.gradle.kts`
- Modify: `android-app/src/main/kotlin/com/orquestrador/ui/theme/Color.kt`

- [ ] **Step 1: Add JUnit4 to build.gradle.kts**

Open `android-app/build.gradle.kts`. In the `dependencies { }` block, add after the last `debugImplementation` line:

```kotlin
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Add AccentCloudflare to Color.kt**

Open `android-app/src/main/kotlin/com/orquestrador/ui/theme/Color.kt`. Add after the last `val Accent...` line:

```kotlin
val AccentCloudflare = Color(0xFFF6821F)
```

- [ ] **Step 3: Commit**

```bash
git add android-app/build.gradle.kts android-app/src/main/kotlin/com/orquestrador/ui/theme/Color.kt
git commit -m "feat: add AccentCloudflare color and JUnit4 test dependency"
```

---

## Task 2: VpnPreferences — Cloudflare Family preference

**Files:**
- Modify: `android-app/src/main/kotlin/com/orquestrador/vpn/VpnPreferences.kt`

- [ ] **Step 1: Write the failing test**

Create `android-app/src/test/kotlin/com/orquestrador/vpn/VpnPreferencesTest.kt`:

> Note: VpnPreferences requires Android SharedPreferences — this test documents the expected interface but cannot run on JVM without Robolectric. The DnsResolverTest (Task 3) provides JVM-runnable coverage. Verify VpnPreferences behavior via integration test or manual run after Task 2.

- [ ] **Step 2: Add CF pref methods to VpnPreferences.kt**

In `VpnPreferences.kt`, add two methods after `getLastAdultSync()`:

```kotlin
fun saveCloudflareFamilyEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_CF_FAMILY, enabled).apply()
}

fun getCloudflareFamilyEnabled(): Boolean = prefs.getBoolean(KEY_CF_FAMILY, true)
```

Add the key constant in the `companion object` after `KEY_LAST_ADULT_SYNC`:

```kotlin
private const val KEY_CF_FAMILY = "cf_family_enabled"
```

- [ ] **Step 3: Commit**

```bash
git add android-app/src/main/kotlin/com/orquestrador/vpn/VpnPreferences.kt
git commit -m "feat: add Cloudflare Family enabled preference"
```

---

## Task 3: DnsResolver — full refactor

**Files:**
- Modify: `android-app/src/main/kotlin/com/orquestrador/vpn/DnsResolver.kt`
- Create: `android-app/src/test/kotlin/com/orquestrador/vpn/DnsResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `android-app/src/test/kotlin/com/orquestrador/vpn/DnsResolverTest.kt`:

```kotlin
package com.orquestrador.vpn

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DnsResolverTest {

    @Before
    fun setUp() {
        DnsResolver.clearCache()
        DnsResolver.setProtect(null)
        DnsResolver.closeSocket()
    }

    @Test
    fun `cache stores and retrieves by name`() {
        val response = byteArrayOf(1, 2, 3, 4)
        DnsResolver.cache("example.com", response)
        assertArrayEquals(response, DnsResolver.getCached("example.com"))
    }

    @Test
    fun `getCached returns null for unknown name`() {
        assertNull(DnsResolver.getCached("notcached.com"))
    }

    @Test
    fun `clearCache removes all entries`() {
        DnsResolver.cache("a.com", byteArrayOf(1))
        DnsResolver.cache("b.com", byteArrayOf(2))
        DnsResolver.clearCache()
        assertNull(DnsResolver.getCached("a.com"))
        assertNull(DnsResolver.getCached("b.com"))
    }

    @Test
    fun `cache evicts eldest entry when capacity exceeded`() {
        for (i in 0 until 101) {
            DnsResolver.cache("domain$i.com", byteArrayOf(i.toByte()))
        }
        // domain0.com was LRU — evicted
        assertNull(DnsResolver.getCached("domain0.com"))
        // domain100.com was most recent — retained
        assertNotNull(DnsResolver.getCached("domain100.com"))
    }

    @Test
    fun `cache access updates LRU order`() {
        for (i in 0 until 100) {
            DnsResolver.cache("domain$i.com", byteArrayOf(i.toByte()))
        }
        // Access domain0 to make it recently used
        DnsResolver.getCached("domain0.com")
        // Adding domain100 should evict domain1 (now LRU), not domain0
        DnsResolver.cache("domain100.com", byteArrayOf(100.toByte()))
        assertNotNull(DnsResolver.getCached("domain0.com"))
        assertNull(DnsResolver.getCached("domain1.com"))
    }

    @Test
    fun `forwardToUpstream returns null when protect rejects socket`() {
        DnsResolver.setProtect { _ -> false }
        val minimalQuery = ByteArray(12)
        val result = DnsResolver.forwardToUpstream(minimalQuery, listOf("1.1.1.3"), null)
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd android-app && ../gradlew test --tests "com.orquestrador.vpn.DnsResolverTest" 2>&1 | tail -20
```

Expected: compile errors — `clearCache`, `setProtect`, `cache`, `getCached` not yet defined; `forwardToUpstream` signature mismatch.

- [ ] **Step 3: Rewrite DnsResolver.kt**

Replace the entire content of `android-app/src/main/kotlin/com/orquestrador/vpn/DnsResolver.kt`:

```kotlin
package com.orquestrador.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object DnsResolver {

    private const val UPSTREAM_PORT = 53
    private const val DNS_TIMEOUT_MS = 2500
    private const val MAX_DNS_RESPONSE = 512
    private const val LRU_CAPACITY = 100
    private const val LOG_TAG = "DnsResolver"

    private var cachedSocket: DatagramSocket? = null
    private val socketLock = ReentrantReadWriteLock()

    @Volatile private var protectFn: ((DatagramSocket) -> Boolean)? = null

    private val lruCache = object : LinkedHashMap<String, ByteArray>(LRU_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ByteArray>) = size > LRU_CAPACITY
    }

    fun setProtect(fn: ((DatagramSocket) -> Boolean)?) {
        protectFn = fn
    }

    @Synchronized
    fun getCached(name: String): ByteArray? = lruCache[name]

    @Synchronized
    fun cache(name: String, response: ByteArray) {
        lruCache[name] = response
    }

    @Synchronized
    fun clearCache() = lruCache.clear()

    private fun getOrCreateSocket(): DatagramSocket? {
        return socketLock.read {
            cachedSocket?.takeIf { !it.isClosed }
        } ?: socketLock.write {
            cachedSocket?.takeIf { !it.isClosed } ?: try {
                val socket = DatagramSocket()
                val fn = protectFn
                if (fn != null && !fn(socket)) {
                    socket.close()
                    null
                } else {
                    socket.soTimeout = DNS_TIMEOUT_MS
                    socket.reuseAddress = true
                    cachedSocket = socket
                    socket
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun tryForward(query: ByteArray, host: String): ByteArray? {
        return try {
            val sock = getOrCreateSocket() ?: return null
            val dest = InetAddress.getByName(host)
            sock.send(DatagramPacket(query, query.size, dest, UPSTREAM_PORT))
            val buf = ByteArray(MAX_DNS_RESPONSE)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            if (resp.length > 0) buf.copyOf(resp.length) else null
        } catch (_: java.net.SocketTimeoutException) {
            socketLock.write { cachedSocket?.close(); cachedSocket = null }
            null
        } catch (_: Exception) {
            socketLock.write { cachedSocket?.close(); cachedSocket = null }
            null
        }
    }

    fun forwardToUpstream(
        query: ByteArray,
        upstreams: List<String>,
        lastResort: String? = null,
    ): ByteArray? {
        for (host in upstreams) {
            val result = tryForward(query, host)
            if (result != null) return result
        }
        if (lastResort != null) {
            System.err.println("[$LOG_TAG] Falha de Upstream DNS")
            return tryForward(query, lastResort)
        }
        return null
    }

    fun extractDnsName(payload: ByteArray): String? {
        return try {
            val buf = ByteBuffer.wrap(payload)
            if (buf.remaining() < 12) return null
            buf.position(12)
            val labels = mutableListOf<String>()
            while (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) return null
                if (buf.remaining() < len) return null
                val label = ByteArray(len)
                buf.get(label)
                labels.add(String(label, Charsets.US_ASCII))
            }
            labels.joinToString(".")
        } catch (_: Exception) {
            null
        }
    }

    fun buildServfailResponse(query: ByteArray): ByteArray {
        return try {
            if (query.size < 12) return query
            val buf = ByteBuffer.wrap(query)
            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)
            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x82.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get()); response.add(buf.get())
                        response.add(buf.get()); response.add(buf.get())
                    }
                    break
                }
            }
            response.toByteArray()
        } catch (_: Exception) {
            query
        }
    }

    fun buildBlockResponse(query: ByteArray): ByteArray {
        return try {
            if (query.size < 12) return query
            val buf = ByteBuffer.wrap(query)
            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)
            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x80.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get()); response.add(buf.get())
                        response.add(buf.get()); response.add(buf.get())
                    }
                    break
                }
            }
            response.add(0xC0.toByte()); response.add(0x0C.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x3C.toByte())
            response.add(0x00); response.add(0x04)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            response.toByteArray()
        } catch (_: Exception) {
            query
        }
    }

    fun closeSocket() {
        socketLock.write {
            cachedSocket?.close()
            cachedSocket = null
        }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
cd android-app && ../gradlew test --tests "com.orquestrador.vpn.DnsResolverTest" 2>&1 | tail -20
```

Expected output:
```
DnsResolverTest > cache stores and retrieves by name PASSED
DnsResolverTest > getCached returns null for unknown name PASSED
DnsResolverTest > clearCache removes all entries PASSED
DnsResolverTest > cache evicts eldest entry when capacity exceeded PASSED
DnsResolverTest > cache access updates LRU order PASSED
DnsResolverTest > forwardToUpstream returns null when protect rejects socket PASSED
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```bash
git add android-app/src/main/kotlin/com/orquestrador/vpn/DnsResolver.kt \
        android-app/src/test/kotlin/com/orquestrador/vpn/DnsResolverTest.kt
git commit -m "feat: refactor DnsResolver — LRU cache, protect(), CF Family upstreams, 2500ms timeout"
```

---

## Task 4: OrquestradorVpnService + VpnGuardWorker — builder and cache integration

**Files:**
- Modify: `android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorVpnService.kt`
- Modify: `android-app/src/main/kotlin/com/orquestrador/vpn/VpnGuardWorker.kt`

- [ ] **Step 1: Update companion object — remove FAKE_DNS_IP, add EXTRA_CF_ENABLED**

In `OrquestradorVpnService.kt`, in the `companion object`:

Remove this line:
```kotlin
private const val FAKE_DNS_IP = "10.0.0.1"
```

Add after `const val EXTRA_MANUAL_DOMAIN`:
```kotlin
const val EXTRA_CF_ENABLED = "cf_enabled"
```

- [ ] **Step 2: Add class-level fields for CF state and upstream tracking**

In `OrquestradorVpnService.kt`, after `private var lastAdultNotifTime = 0L`, add:

```kotlin
private var cloudflareEnabled: Boolean = true
private var currentUpstreams: List<String> = listOf("1.1.1.3", "1.0.0.3")
private var lastResortDns: String? = "8.8.8.8"
```

- [ ] **Step 3: Update onStartCommand() to read CF extra**

In `onStartCommand()`, add after `enabledCategories = cats`:

```kotlin
cloudflareEnabled = intent?.getBooleanExtra(EXTRA_CF_ENABLED, true) ?: true
```

- [ ] **Step 4: Replace startVpn()**

Replace the entire `startVpn()` function:

```kotlin
private fun startVpn() {
    tunFd?.close()
    DnsResolver.clearCache()

    val builder = Builder()
        .setSession("Orquestrador VPN")
        .addAddress(VPN_ADDRESS, 24)
        .addRoute("0.0.0.0", 0)
        .setBlocking(false)

    if (cloudflareEnabled) {
        builder
            .addDnsServer("1.1.1.3")
            .addDnsServer("1.0.0.3")
            .addRoute("1.1.1.3", 32)
            .addRoute("1.0.0.3", 32)
        try {
            builder
                .addDnsServer("2606:4700:4700::1113")
                .addDnsServer("2606:4700:4700::1003")
                .addRoute("::", 0)
        } catch (_: Exception) { /* IPv6 not supported on device */ }
        currentUpstreams = listOf("1.1.1.3", "1.0.0.3")
        lastResortDns = "8.8.8.8"
    } else {
        builder
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addRoute("8.8.8.8", 32)
            .addRoute("8.8.4.4", 32)
        currentUpstreams = listOf("8.8.8.8", "8.8.4.4")
        lastResortDns = null
    }

    tunFd = builder.establish() ?: run {
        stopSelf()
        return
    }

    DnsResolver.setProtect { socket -> protect(socket) }
    scope.launch { runPacketLoop() }
}
```

- [ ] **Step 5: Update runPacketLoop() — add LRU cache + new forwardToUpstream signature**

In `runPacketLoop()`, replace the `else ->` branch:

Old:
```kotlin
else -> DnsResolver.forwardToUpstream(dnsPacket.dnsPayload)
    ?: DnsResolver.buildServfailResponse(dnsPacket.dnsPayload)
```

New:
```kotlin
else -> {
    val lowerName = name.lowercase()
    DnsResolver.getCached(lowerName)
        ?: DnsResolver.forwardToUpstream(dnsPacket.dnsPayload, currentUpstreams, lastResortDns)
            ?.also { DnsResolver.cache(lowerName, it) }
        ?: DnsResolver.buildServfailResponse(dnsPacket.dnsPayload)
}
```

- [ ] **Step 6: Update stopVpn() — release protect and clear cache**

Replace the `stopVpn()` function:

```kotlin
private fun stopVpn() {
    isRunning = false
    globalAdultCache = null
    scope.cancel()
    tunFd?.close()
    tunFd = null
    db?.close()
    db = null
    DnsResolver.setProtect(null)
    DnsResolver.clearCache()
    DnsResolver.closeSocket()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}
```

- [ ] **Step 7: Fix onRevoke() — include CF extra in restart intent**

In `onRevoke()`, replace the `startForegroundService(...)` call:

Old:
```kotlin
startForegroundService(
    Intent(this, OrquestradorVpnService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_ENABLED_CATEGORIES, prefs.getEnabledCategories().toTypedArray())
)
```

New:
```kotlin
startForegroundService(
    Intent(this, OrquestradorVpnService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_ENABLED_CATEGORIES, prefs.getEnabledCategories().toTypedArray())
        .putExtra(EXTRA_CF_ENABLED, prefs.getCloudflareFamilyEnabled())
)
```

- [ ] **Step 8: Fix VpnGuardWorker — include CF extra on auto-restart**

In `VpnGuardWorker.kt`, replace the `startForegroundService(...)` call:

Old:
```kotlin
applicationContext.startForegroundService(
    Intent(applicationContext, OrquestradorVpnService::class.java)
        .setAction(OrquestradorVpnService.ACTION_START)
        .putExtra(
            OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES,
            prefs.getEnabledCategories().toTypedArray()
        )
)
```

New:
```kotlin
applicationContext.startForegroundService(
    Intent(applicationContext, OrquestradorVpnService::class.java)
        .setAction(OrquestradorVpnService.ACTION_START)
        .putExtra(
            OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES,
            prefs.getEnabledCategories().toTypedArray()
        )
        .putExtra(
            OrquestradorVpnService.EXTRA_CF_ENABLED,
            prefs.getCloudflareFamilyEnabled()
        )
)
```

- [ ] **Step 9: Commit**

```bash
git add android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorVpnService.kt \
        android-app/src/main/kotlin/com/orquestrador/vpn/VpnGuardWorker.kt
git commit -m "feat: integrate CF Family DNS into VPN builder, add LRU cache and socket protection"
```

---

## Task 5: VpnUiState + OrquestradorViewModel — cloudflareEnabled toggle

**Files:**
- Modify: `android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorViewModel.kt`

- [ ] **Step 1: Add cloudflareEnabled to VpnUiState**

Replace the `VpnUiState` data class:

```kotlin
data class VpnUiState(
    val isVpnRunning: Boolean = false,
    val socialEnabled: Boolean = true,
    val adultEnabled: Boolean = true,
    val mangaEnabled: Boolean = false,
    val ublockEnabled: Boolean = false,
    val cloudflareEnabled: Boolean = true,
    val needsVpnPermission: Boolean = false,
    val needsOverlayPermission: Boolean = false,
)
```

- [ ] **Step 2: Update init{} to restore CF preference**

Replace the `init { }` block:

```kotlin
init {
    val prefs = VpnPreferences(app)
    val savedCats = prefs.getEnabledCategories()
    val cfEnabled = prefs.getCloudflareFamilyEnabled()
    if (savedCats.isNotEmpty()) {
        _state.value = _state.value.copy(
            isVpnRunning = OrquestradorVpnService.isRunning,
            socialEnabled = BlockList.Category.SOCIAL.name in savedCats,
            adultEnabled = BlockList.Category.ADULT.name in savedCats,
            mangaEnabled = BlockList.Category.MANGA.name in savedCats,
            ublockEnabled = BlockList.Category.UBLOCK.name in savedCats,
            cloudflareEnabled = cfEnabled,
        )
    } else {
        _state.value = _state.value.copy(
            isVpnRunning = OrquestradorVpnService.isRunning,
            cloudflareEnabled = cfEnabled,
        )
    }
}
```

- [ ] **Step 3: Add onCloudflareFamilyToggle()**

Add this method after `onCategoryToggle()`:

```kotlin
fun onCloudflareFamilyToggle(enabled: Boolean) {
    _state.value = _state.value.copy(cloudflareEnabled = enabled)
    VpnPreferences(getApplication()).saveCloudflareFamilyEnabled(enabled)
    if (_state.value.isVpnRunning) updateVpnCategories()
}
```

- [ ] **Step 4: Update buildServiceIntent() to include CF extra**

Replace `buildServiceIntent()`:

```kotlin
private fun buildServiceIntent(action: String): Intent {
    val cats = buildList {
        if (_state.value.socialEnabled) add(BlockList.Category.SOCIAL.name)
        if (_state.value.adultEnabled) add(BlockList.Category.ADULT.name)
        if (_state.value.mangaEnabled) add(BlockList.Category.MANGA.name)
        if (_state.value.ublockEnabled) add(BlockList.Category.UBLOCK.name)
    }.toTypedArray()
    return Intent(getApplication(), OrquestradorVpnService::class.java)
        .setAction(action)
        .putExtra(OrquestradorVpnService.EXTRA_ENABLED_CATEGORIES, cats)
        .putExtra(OrquestradorVpnService.EXTRA_CF_ENABLED, _state.value.cloudflareEnabled)
}
```

- [ ] **Step 5: Commit**

```bash
git add android-app/src/main/kotlin/com/orquestrador/vpn/OrquestradorViewModel.kt
git commit -m "feat: add cloudflareEnabled state and toggle to VpnUiState and ViewModel"
```

---

## Task 6: OrquestradorScreen — Cloudflare Family card

**Files:**
- Modify: `android-app/src/main/kotlin/com/orquestrador/ui/OrquestradorScreen.kt`

- [ ] **Step 1: Add imports**

In `OrquestradorScreen.kt`, add these two imports after the existing `import androidx.compose.material.icons.outlined.*` block:

```kotlin
import androidx.compose.material.icons.outlined.Shield
import com.orquestrador.ui.theme.AccentCloudflare
```

- [ ] **Step 2: Add CloudflareCard composable**

Add this composable after the `ModuleCard` composable (after line ~585):

```kotlin
// ─────────────────────────────────────────────
// Cloudflare Family card
// ─────────────────────────────────────────────

@Composable
private fun CloudflareCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val accentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(400),
        label = "cf_accent_alpha",
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) AccentCloudflare.copy(alpha = 0.45f) else CardBorderIdle,
        animationSpec = tween(400),
        label = "cf_border",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
            .background(CardSurface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    AccentCloudflare.copy(alpha = 0.08f + 0.10f * accentAlpha),
                    RoundedCornerShape(11.dp),
                )
                .border(
                    0.5.dp,
                    AccentCloudflare.copy(alpha = 0.25f * accentAlpha),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = AccentCloudflare.copy(alpha = accentAlpha),
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "Proteção Familiar Cloudflare",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) TextPrimary else TextSecondary,
            )
            Text(
                text = "1.1.1.3 · Filtra adulto e malware",
                style = MaterialTheme.typography.bodyMedium,
                color = TextDim,
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AccentCloudflare.copy(alpha = 0.65f),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = SwitchTrackOff,
                uncheckedThumbColor = TextDim,
            ),
        )
    }
}
```

- [ ] **Step 3: Wire CloudflareCard into the main Column**

In `OrquestradorScreen`'s main `Column`, replace:

```kotlin
SectionHeader(title = "MÓDULOS DE BLOQUEIO")
```

With:

```kotlin
SectionHeader(title = "PROTEÇÃO DE REDE")

CloudflareCard(
    enabled = state.cloudflareEnabled,
    onToggle = { enabled -> withPin(enabled) { viewModel.onCloudflareFamilyToggle(enabled) } },
)

SectionHeader(title = "MÓDULOS DE BLOQUEIO")
```

- [ ] **Step 4: Build to verify no compile errors**

```bash
cd android-app && ../gradlew assembleDebug 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add android-app/src/main/kotlin/com/orquestrador/ui/OrquestradorScreen.kt
git commit -m "feat: add Cloudflare Family PIN-gated toggle card to main screen"
```

---

## Task 7: Full test run and final verification

- [ ] **Step 1: Run all unit tests**

```bash
cd android-app && ../gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all DnsResolverTest tests PASSED.

- [ ] **Step 2: Full debug build**

```bash
cd android-app && ../gradlew assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual verification checklist**

Install APK on device/emulator and verify:
- [ ] CF Family card appears between VPN toggle and "MÓDULOS DE BLOQUEIO" section
- [ ] CF card is ON by default
- [ ] Disabling CF card prompts PIN (if PIN is set)
- [ ] Enabling CF card does NOT prompt PIN
- [ ] VPN restarts when CF toggle changes (observe notification briefly disappears/reappears)
- [ ] With CF ON: browse to a safe site — DNS resolves normally
- [ ] With CF ON: browse to known adult site — blocked (either by local filter or CF Family)
- [ ] Toggle CF OFF: DNS still resolves via 8.8.8.8
- [ ] Kill app, reopen: CF toggle state persists
- [ ] VPN guard worker restart: CF state preserved (check via logcat after force-stopping VPN)

- [ ] **Step 4: Commit if any final fixes applied**

```bash
git add -p  # stage only what changed
git commit -m "fix: <describe any fixes found during verification>"
```
