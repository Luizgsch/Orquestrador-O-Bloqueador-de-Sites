# VPN Persistence Shield — Design Spec
Date: 2026-05-08

## Goal

Make OrquestradorVpnService resilient against external kills: system battery optimization, user disabling via Settings, and device reboots. When desired state is ACTIVE, the app must resist disconnection and immediately alert the user.

---

## Architecture

```
VpnPreferences (SharedPrefs)
  ├── desiredVpnActive: Boolean
  └── enabledCategories: Set<String>

OrquestradorVpnService
  ├── onRevoke() → notification (high-priority + full-screen intent) + immediate restart attempt
  └── onStartCommand: persist desired state to VpnPreferences

VpnMonitorReceiver (BroadcastReceiver)
  └── BOOT_COMPLETED → read VpnPreferences → startForegroundService if desired=ACTIVE

VpnGuardWorker (WorkManager PeriodicWork, 15min, Expedited)
  └── read VpnPreferences desired=ACTIVE + VPN not running → restart silently

OrquestradorViewModel
  └── save/load state from VpnPreferences on every toggle
```

---

## Components

### 1. VpnPreferences.kt
- `android.content.SharedPreferences` wrapper
- Keys: `desired_vpn_active` (Boolean), `enabled_categories` (StringSet)
- Functions: `save(desiredActive, categories)`, `getDesiredActive(): Boolean`, `getEnabledCategories(): Set<String>`
- Instantiated from Application context to avoid leaks

### 2. OrquestradorVpnService — onRevoke() changes
Called by Android when VPN is revoked externally (user in Settings, system kill).

**Sequence:**
1. Read `VpnPreferences.getDesiredActive()`
2. If `true`:
   a. Build `Intent(this, MainActivity::class.java)` as PendingIntent
   b. Show notification on channel `vpn_alert` (IMPORTANCE_HIGH):
      - Title: "Orquestrador desativado"
      - Text: "VPN foi desligada. Toque para reativar."
      - `setFullScreenIntent(pendingIntent, highPriority=true)` — pops MainActivity like a call if `USE_FULL_SCREEN_INTENT` granted
   c. Attempt `startForegroundService(ACTION_START)` with saved categories — works if VPN permission still valid
3. Call `stopVpn()` to clean up resources

**onStartCommand changes:**
- On ACTION_START: save `desiredVpnActive=true` + current categories to `VpnPreferences`
- On ACTION_STOP: save `desiredVpnActive=false` to `VpnPreferences`

### 3. VpnMonitorReceiver.kt
`BroadcastReceiver` registered statically in Manifest.

**Handles:** `android.intent.action.BOOT_COMPLETED`

**Logic:**
1. Read `VpnPreferences.getDesiredActive()`
2. If `true`: call `startForegroundService(Intent(ACTION_START) + saved categories)`

### 4. VpnGuardWorker.kt
`CoroutineWorker` via WorkManager.

**Scheduling:**
- `PeriodicWorkRequestBuilder<VpnGuardWorker>(15, TimeUnit.MINUTES)`
- `.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` — prioritizes within system budget
- Enqueued with `ExistingPeriodicWorkPolicy.KEEP` on app start

**Logic:**
1. Read `VpnPreferences.getDesiredActive()` — if false, return `Result.success()`
2. Check `OrquestradorVpnService.isRunning` (companion object flag, set true/false in onStartCommand/onDestroy) — avoids deprecated `ActivityManager.getRunningServices()`
3. If not running: `applicationContext.startForegroundService(ACTION_START + saved categories)`
4. Return `Result.success()`

**Enqueue point:** `MainActivity.onCreate()` — idempotent via KEEP policy.

### 5. OrquestradorViewModel — persistence
- Inject `VpnPreferences` via Application context
- `init {}`: load saved state → populate `VpnUiState` from SharedPrefs
- `startVpnService()`: save desired=true + categories before starting service
- `stopVpnService()`: save desired=false before stopping

---

## Manifest Changes

```xml
<!-- Permissions to add -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<!-- Receiver to add inside <application> -->
<receiver
    android:name=".vpn.VpnMonitorReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

`FOREGROUND_SERVICE_SPECIAL_USE` already present. No change needed.

---

## build.gradle.kts Changes

```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.1")
```

WorkManager 2.7+ required for expedited requests.

---

## Notification Channels

| Channel ID    | Importance | Purpose                        |
|---------------|-----------|-------------------------------|
| `vpn_service` | LOW       | Persistent foreground notification (existing) |
| `vpn_alert`   | HIGH      | VPN stopped alert with full-screen intent |

`vpn_alert` channel created in `createNotificationChannel()` alongside existing channel.

---

## Constraints & Edge Cases

| Scenario | Behavior |
|----------|----------|
| User revokes VPN in Settings | `onRevoke()` fires: notification + full-screen intent + restart attempt |
| System kills service (battery) | `onRevoke()` fires (same path) |
| Device reboot | `VpnMonitorReceiver` restarts on BOOT_COMPLETED |
| WorkManager 15min guard | Catches any case where onRevoke didn't fire or restart failed |
| User explicitly stops via app UI | `desiredVpnActive=false` saved → no auto-restart |
| VPN permission revoked by user | `startForegroundService` will fail silently; notification still shows |
| `USE_FULL_SCREEN_INTENT` not granted (Android 14+) | Falls back to standard high-priority notification |

---

## Files Created/Modified

| File | Action |
|------|--------|
| `vpn/VpnPreferences.kt` | CREATE |
| `vpn/VpnMonitorReceiver.kt` | CREATE |
| `vpn/VpnGuardWorker.kt` | CREATE |
| `vpn/OrquestradorVpnService.kt` | MODIFY: onRevoke(), onStartCommand(), add `isRunning` companion flag |
| `vpn/OrquestradorViewModel.kt` | MODIFY: load/save VpnPreferences |
| `AndroidManifest.xml` | MODIFY: permissions + receiver |
| `build.gradle.kts` | MODIFY: add work-runtime-ktx |
