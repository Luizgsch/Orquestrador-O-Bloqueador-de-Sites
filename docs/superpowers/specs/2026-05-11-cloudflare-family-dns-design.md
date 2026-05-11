# Cloudflare Family DNS — Network Engine Redesign

**Date:** 2026-05-11  
**Status:** Approved  
**Scope:** DnsResolver, OrquestradorVpnService, ViewModel, UI

---

## 1. Goal

Replace generic upstream DNS (8.8.8.8 / 1.1.1.1) with Cloudflare Family (1.1.1.3 / 1.0.0.3) as the forwarding backbone. Add resilience, LRU caching, socket protection, IPv6 declarations, and a PIN-gated UI toggle.

---

## 2. Architecture

### Data Flow

```
Device DNS query (UDP port 53)
        │
        ▼
TUN interface (VPN tunnel)
        │
        ▼
  parseDnsFromIpPacket()   ← IPv4 only (protocol 0x11)
        │
        ▼
  extractDnsName()
        │
   LOCAL FILTER (no network, always active regardless of CF toggle)
   1. MANUAL blocks (allDomains map)
   2. Category DB (SOCIAL / MANGA / UBLOCK)
   3. ADULT pipeline: TLD → heuristic → static → globalAdultCache
   4. Heuristic manga patterns
        │
        ├─ blocked → buildBlockResponse() → back to device (do NOT cache)
        │
        ▼
   LRU CACHE (100 entries, in-memory, keyed by query name)
        │
        ├─ cache hit → return cached response → back to device
        │
        ▼
   CF FAMILY ENABLED?
   YES → upstreams = ["1.1.1.3", "1.0.0.3"]   timeout 2500ms each
   NO  → upstreams = ["8.8.8.8", "8.8.4.4"]   timeout 2500ms each
        │
        ├─ primary success → store in LRU → return to device
        ├─ primary fail → try secondary
        ├─ secondary success → store in LRU → return to device
        └─ secondary fail →
             CF ON:  last-resort 8.8.8.8 (2500ms), log "Falha de Upstream DNS"
                     ├─ success → return (not cached)
                     └─ fail → buildServfailResponse()
             CF OFF: buildServfailResponse() directly
```

### VPN Builder Config

**CF Family ON:**
```
addAddress("10.0.0.2", 24)
addRoute("0.0.0.0", 0)
addDnsServer("1.1.1.3")
addDnsServer("1.0.0.3")
addRoute("1.1.1.3", 32)
addRoute("1.0.0.3", 32)
addDnsServer("2606:4700:4700::1113")   // IPv6, declared only
addDnsServer("2606:4700:4700::1003")   // IPv6, declared only
addRoute("::", 0)                      // IPv6 catch-all
```

**CF Family OFF:**
```
addAddress("10.0.0.2", 24)
addRoute("0.0.0.0", 0)
addDnsServer("8.8.8.8")
addDnsServer("8.8.4.4")
addRoute("8.8.8.8", 32)
addRoute("8.8.4.4", 32)
```

`FAKE_DNS_IP` (10.0.0.1) removed. Packet loop intercepts all UDP/53 regardless of destination IP — behavior unchanged.

---

## 3. Components

### `DnsResolver.kt` (major refactor)

- Remove static `dnsServers` list
- Accept `upstreamServers: List<String>` in `forwardToUpstream()`
- Accept `protect: (DatagramSocket) -> Boolean` function stored at init (set by VpnService on start, nulled on stop)
- Timeout: `DNS_TIMEOUT_MS = 2500`
- `getOrCreateSocket()`: call `protect(socket)` immediately after creation; if protect returns false, close and return null
- `DnsLruCache`: `LinkedHashMap(100, 0.75f, true)` with `removeEldestEntry { size > 100 }`, `@Synchronized`
- `forwardToUpstream()` signature: `fun forwardToUpstream(query: ByteArray, upstreams: List<String>): ByteArray?`
- Three-tier fallback internally: primary → secondary → last-resort 8.8.8.8
- Cache lookup/store: in `runPacketLoop` in VpnService (not inside DnsResolver), so blocked domains are never cached

### `OrquestradorVpnService.kt`

- Remove `FAKE_DNS_IP` constant
- Add `EXTRA_CF_ENABLED = "cf_enabled"` companion constant
- Add `private var cloudflareEnabled: Boolean = true` field
- `onStartCommand()`: read `intent.getBooleanExtra(EXTRA_CF_ENABLED, true)` → set `cloudflareEnabled`
- `startVpn()`: branch on `cloudflareEnabled` for builder DNS/routes
- After `establish()`: call `DnsResolver.setProtect(this::protect)`
- `stopVpn()`: call `DnsResolver.setProtect(null)` + `DnsResolver.clearCache()`
- `runPacketLoop()`: add LRU cache check before `forwardToUpstream`; pass `upstreams` based on `cloudflareEnabled`

### `VpnUiState` + `OrquestradorViewModel.kt`

- `VpnUiState`: add `cloudflareEnabled: Boolean = true`
- `init {}`: restore from `VpnPreferences.getCloudflareFamilyEnabled()`
- `onCloudflareFamilyToggle(enabled: Boolean)`:
  - Update state
  - `VpnPreferences.saveCloudflareFamilyEnabled(enabled)`
  - If VPN running: `updateVpnCategories()` (triggers VPN restart with new config)
- `buildServiceIntent()`: add `.putExtra(EXTRA_CF_ENABLED, state.cloudflareEnabled)`
- PIN gate: disable requires PIN (same `withPin(isEnabling = false)` pattern)

### `VpnPreferences.kt`

```kotlin
fun saveCloudflareFamilyEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_CF_FAMILY, enabled).apply()
}
fun getCloudflareFamilyEnabled(): Boolean = prefs.getBoolean(KEY_CF_FAMILY, true)

companion object {
    // existing keys...
    private const val KEY_CF_FAMILY = "cf_family_enabled"
}
```

### `OrquestradorScreen.kt`

- New `ProtectionCard` entry for Cloudflare Family
- Position: first card after VPN toggle, before category cards
- Icon: `Icons.Outlined.Security` (already imported)
- Color: `AccentCloudflare`
- Label: `"Proteção Familiar Cloudflare"`
- Subtitle: `"1.1.1.3 · Filtra adulto e malware"`
- PIN gate on disable via existing `withPin()` mechanism

### `Color.kt`

```kotlin
val AccentCloudflare = Color(0xFFF6821F)  // Cloudflare orange
```

---

## 4. Error Handling

| Scenario | Behavior |
|----------|----------|
| Primary CF timeout | Retry secondary CF (1.0.0.3), 2500ms |
| Both CF fail | Last-resort 8.8.8.8, log "Falha de Upstream DNS" |
| All upstreams fail | `buildServfailResponse()` — local blocks still enforced |
| `protect()` returns false | Close socket, return `buildServfailResponse()` — never forward unprotected |
| CF toggle off mid-session | VPN restarts with 8.8.8.8 builder config |
| App restart CF disabled | `VpnPreferences` restores `cloudflareEnabled=false` |
| Network loss | Timeout chain runs; local filter unaffected |
| VPN revoked (Relentless Mode) | Existing `onRevoke()` flow unchanged |

---

## 5. Concurrency

- DNS packet loop: single coroutine on `Dispatchers.IO`
- LRU cache: `@Synchronized` on get/put/clear
- DatagramSocket: one cached socket, protected on creation, `ReentrantReadWriteLock` unchanged
- VPN restart (toggle change): `startVpn()` closes old `tunFd`, establishes new builder, clears cache

---

## 6. IPv6

- IPv6 DNS servers declared in VPN builder (CF Family ON only)
- Packet loop handles IPv4 only (existing `protocol == 0x11` check at byte 9)
- IPv6 DNS queries routed via `addRoute("::", 0)` and served natively by Android using declared IPv6 DNS servers
- No IPv6 forwarding socket implemented — out of scope

---

## 7. Files Changed

| File | Change type |
|------|-------------|
| `vpn/DnsResolver.kt` | Major refactor |
| `vpn/OrquestradorVpnService.kt` | Medium — builder + cache integration |
| `vpn/OrquestradorViewModel.kt` | Small — new toggle + intent extra |
| `vpn/VpnPreferences.kt` | Small — new key |
| `ui/OrquestradorScreen.kt` | Small — new card |
| `ui/theme/Color.kt` | Trivial — one color |

**No new files.**

---

## 8. Out of Scope

- DNS over HTTPS (DoH)
- Per-app DNS routing
- DNS query logging / analytics UI
- Persistent LRU cache (disk)
