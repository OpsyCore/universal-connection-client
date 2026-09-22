# PROJECT STATUS AUDIT — Universal Connection Client

Audited at commit `bdcf860` on branch `arena/01a0aa91-universal-connection-client`
(43 commits since `4e4fe74 Initial commit`). Date: 2026-09-17.

Method: repository inspection (sources, tests, Gradle, manifests, CI workflow,
GitHub check-runs). No production code was modified for this audit.

Verification vocabulary used throughout:

| Tag | Meaning |
|---|---|
| **U** | JVM unit-tested |
| **C** | passes in GitHub Actions CI (`Build & test` job) |
| **B** | compiles / assembles in CI (`assembleSingboxDebug`) |
| **D** | executed on a physical Android device — **none in this project** |
| **N** | network behaviour observed on a device — **none in this project** |

Environment limitation (unchanged all project): no local Gradle, no emulator,
no device. CI is the only build/test authority. Nothing below is D or N.

---

## 1. Phase Status

| Phase | Status | Evidence | What is missing for COMPLETE |
|---|---|---|---|
| 0 — Repository / Core audit | **COMPLETE** | `PROJECT_AUDIT.md`, `docs/CORE_DECISION.md`, `docs/CORE_LICENSE_AUDIT.md`; decision approved by owner | — |
| 1 — Foundation / Engine / VPN / App shell | **COMPLETE (code) / DEVICE VERIFICATION REQUIRED** | modules `core/model`, `engine-api` (state machine, 24 tests), `singbox-config`, `engine-singbox` (libbox binding), `vpn` (`UcVpnService`, tunnel host, network monitor), `app` Home; CI builds APK | tunnel never established on a device |
| 2a — Config engine / parsers | **COMPLETE** | `core/config` 87 tests: 10 protocols, share links, sing-box JSON, base64 bodies, format detection, classified errors | Xray/Clash JSON and WireGuard `.conf` import not implemented (documented as out of scope) |
| 2b — Add configuration (paste / clipboard / QR / file / subscription) | **COMPLETE (code) / DEVICE VERIFICATION REQUIRED** | `ImportRepository`, `AddConfigViewModel` + tests, CameraX + ML Kit QR scanner, SAF document picker, preview→confirm→save, fingerprint dedupe, capability check | QR camera path and document picker only build-verified |
| 3 — Servers / subscriptions / refresh / merge / export | **COMPLETE (code)** | `ServerRepository`, `SubscriptionRefresher` (edit-preserving merge), `ShareLinkExporter`, WorkManager periodic refresh, Servers screen; tests in app + config | custom user groups (beyond subscription groups) not implemented; WorkManager scheduling not observed on device |
| 4 — Secure storage / encryption / Keystore / migration | **COMPLETE (code) / DEVICE VERIFICATION REQUIRED** | `AesGcmFileCodec`, `SecureFile` (tmp + rename, quarantine), `KeystoreKeyProvider`, one-time migration + shred; 12 tests with a fake key provider | Android Keystore itself cannot run on JVM — key generation/unlock never exercised on a device |
| 5 — Routing / DNS / per-app / kill switch / logs / settings | **COMPLETE (code) / DEVICE VERIFICATION REQUIRED** | `docs/CAPABILITIES.md` matrix; typed `RoutingRule`, DNS options, per-app gated on capabilities, kill-switch **status card only** (Android lockdown flags), sanitised categorised logs, 8-section Settings; +12 tests | every network behaviour (rules steering traffic, DNS hijack, per-app isolation, reconnect on Wi-Fi↔mobile) |
| 6 — Error classification & diagnostics *(partially executed earlier in the session, before the owner froze scope)* | **PARTIAL** | `ErrorClassifier` (+3 tests), error hints, TCP reachability tester (+4 tests) landed in `d4bd4d2`/`8888e25`. Smart selection landed in the Feature Completion Pass (`:core:smart`, see `docs/SMART_SELECTION.md`): health model, TCP connection test, deterministic ranking, bounded failover, Home/Servers/Smart UI. | Smart selection: **CODE + UNIT + CI**; device verification NOT AVAILABLE |
| 7 — About / licences / theme / RTL polish | **PARTIAL** | licences dialog from `Notices`, theme mode, `supportsRtl`, fa strings exist. No dedicated About screen, no RTL screenshot review | RTL visual verification, full licence texts (GPLv3 text of sing-box not bundled) |
| 8 — Security review | **PARTIAL** | `docs/SECURITY.md` reviewed items; exported components audited; no penetration/leak testing | device-side DNS-leak test, backup-exclusion verification |
| 9 — Release config | **NOT STARTED** | `release { isMinifyEnabled = true }` exists but no signing config, no release CI job, no AAB, no R8 verification of libbox/Compose/serialization at runtime | see §8 |
| Final — Device verification | **NOT STARTED / BLOCKED** (no device available to the agent) | `docs/TESTING.md` manual checklist written | a human with a device |

---

## 2. Feature Inventory

Legend: Impl = implemented in code · Tested = JVM unit test exists · CI = passes CI at `bdcf860` · Dev = device verified.

| Feature | Implemented | Tested | CI | Device | Notes |
|---|---|---|---|---|---|
| VLESS (uri, reality, flow, ws/grpc/http/httpupgrade) | Yes | Yes | Yes | No | parser + sing-box outbound |
| VMess (base64 JSON + uri) | Yes | Yes | Yes | No | |
| Trojan | Yes | Yes | Yes | No | |
| Shadowsocks (incl. 2022 ciphers, SIP002) | Yes | Yes | Yes | No | |
| Hysteria (v1) | Yes | Yes | Yes | No | UDP only; not reachability-testable |
| Hysteria2 | Yes | Yes | Yes | No | port hopping parsed |
| TUIC | Yes | Yes | Yes | No | |
| WireGuard (`wireguard://` uri) | Yes | Yes | Yes | No | sing-box endpoint; `.conf` import **not** implemented |
| SOCKS | Yes | Yes | Yes | No | |
| HTTP proxy | Yes | Yes | Yes | No | |
| sing-box JSON import | Yes | Yes | Yes | No | outbounds extracted; Xray/Clash JSON not supported |
| Base64 subscription bodies | Yes | Yes | Yes | No | |
| Subscription URL (https only, size cap, user-info headers) | Yes | Yes | Yes | No | fetcher tested with fakes; real HTTP not exercised in CI |
| QR import (CameraX + ML Kit, preview/confirm) | Yes | ViewModel only | Build | No | camera path device-only |
| Clipboard import | Yes | ViewModel only | Build | No | |
| File import (SAF, size limit) | Yes | Repository | Build | No | |
| Server management (list, select) | Yes | Yes | Yes | No | |
| Favorites | Yes | Yes | Yes | No | |
| Search (name/host/protocol/tag) | Yes | Yes | Yes | No | |
| Groups | Partial | Yes | Yes | No | subscription groups + "manual" only; no user-defined groups |
| Rename | Yes | Yes | Yes | No | sets `userRenamed`, preserved on merge |
| Delete (single, multi, subscription group) | Yes | Yes | Yes | No | active profile protected |
| Share / export links | Yes | Yes | Yes | No | `ShareLinkExporter`; system share sheet |
| Subscription refresh (manual) | Yes | Yes | Yes | No | |
| Subscription merge (edit-preserving) | Yes | Yes | Yes | No | |
| Auto refresh (WorkManager periodic) | Yes | Refresher yes; Worker no | Build | No | scheduling never observed |
| Encryption at rest (AES-256-GCM, AAD magic) | Yes | Yes | Yes | No | |
| Android Keystore key | Yes | Fake provider only | Build | No | real Keystore untestable on JVM |
| VPN / TUN (`VpnService`, auto_route, gVisor mixed stack) | Yes | Manager only | Build | No | **never established on a device** |
| UDP | Core | No | Build | No | |
| IPv4 | Yes | Yes (JSON) | Yes | No | |
| IPv6 (toggle) | Yes | Yes (JSON) | Yes | No | |
| DNS (remote via proxy, direct, typed servers) | Yes | Yes (JSON) | Yes | No | no leak-protection claim |
| DNS routing (proxy host → direct resolver; profile override) | Yes | Yes | Yes | No | |
| DNS hijacking (`hijack-dns` rule) | Yes | Yes (JSON) | Yes | No | |
| Routing rules (domain/suffix/keyword/CIDR → direct/proxy/block) | Yes | Yes | Yes | No | Phase 5 |
| Split tunnelling (LAN bypass) | Yes | Yes | Yes | No | |
| Per-app routing (include/exclude) | Yes | Yes (mapping) | Yes | No | gated on `CoreCapabilities.perAppRouting` |
| Kill switch | **No (by design)** | Status plumbing yes | Yes | No | only Android lockdown; app shows real OS state read-only |
| Auto reconnect (backoff, probe, core restart) | Yes | Yes | Yes | No | |
| Network change handling (monitor → reconnect, `resetNetwork`) | Yes | Yes | Yes | No | |
| Traffic statistics (libbox status) | Yes | No | Build | No | |
| Logs (core + manager, categories, filter, copy/share, sanitiser) | Yes | Yes | Yes | No | in-memory only |
| Diagnostics (error classes + hints, TCP reachability, log level) | Yes | Yes | Yes | No | |
| Background operation (foreground service, notification) | Yes | No | Build | No | |
| Reboot persistence | **Partial** | No | Build | No | `RECEIVE_BOOT_COMPLETED` declared but **no BroadcastReceiver**; relies on Android always-on or START_STICKY restart only |
| Persian / English, RTL | Yes | No | Build (lint) | No | strings complete; RTL layout unreviewed |
| Dark / light / system theme | Yes | No | Build | No | |
| Smart server selection | **Yes** | Yes (`:core:smart` 40+ tests, coordinator 11, stores 2, VM) | Yes | **No** (no device) | health/ranking/failover/test-all per `docs/SMART_SELECTION.md` |
| Profile field editing / detach from subscription | **No** | — | — | — | backlog |

---

## 3. Security Audit

### VERIFIED BY CODE / TEST (JVM + CI)

| Item | Implementation | Test |
|---|---|---|
| Credential redaction in model dumps | `Authentication.toString` redacts; `ConnectionProfile.toLogString` | `core/model` tests |
| Core error redaction | `ErrorClassifier.redact` (kv pairs + UUID) in addition to adapter redaction | `ErrorClassifierTest` |
| Log sanitisation (ingest **and** export) | `LogSanitizer`: URL userinfo, query tokens, vmess/ss blobs, kv secrets, UUID, base64 keys | `LogSanitizerTest` (5), `LogBufferTest` |
| Encrypted storage | `AesGcmFileCodec` AES-256-GCM, random IV, `UCC1` magic as AAD; tamper/truncate/wrong-key rejected | `AesGcmFileCodecTest` |
| Atomic writes | `SecureFile` writes `.enc.tmp` then `renameTo` | `SecureFileTest` |
| Corrupt-data handling | undecryptable file → `*.enc.corrupt-<ts>` quarantine, Home notice, store stays writable | `EncryptedStoresTest` |
| Legacy plaintext migration + shred | one-time migrate `profiles.json` → `.enc`, overwrite + delete | `SecureFileTest`, `EncryptedStoresTest` |
| HTTPS-only subscriptions | `HttpSubscriptionFetcher` rejects non-https; `network_security_config` `cleartextTrafficPermitted=false` | fetcher tests |
| Subscription credentials | URL stored inside encrypted `subscriptions.enc`; never logged (sanitiser strips userinfo/query) | `EncryptedStoresTest`, `LogSanitizerTest` |
| Import size limits | file and subscription body caps with typed errors | `ImportRepositoryTest`, refresher tests |
| Settings contain no secrets | `ConnectionSettings` = DNS specs, flags, rules, packages → plain SharedPreferences acceptable | `ConnectionSettingsTest` (round-trip) |
| Backup exclusion | `allowBackup=false`, `data_extraction_rules` exclude all domains | manifest inspection only |
| Exported components | only `MainActivity` (launcher) and `UcVpnService` (guarded by `BIND_VPN_SERVICE`) | manifest inspection |
| Clipboard | read only on explicit user action; never written except "copy logs" (sanitised) | code inspection |

### REQUIRES DEVICE TEST

- Android Keystore key generation, `UCC1` file decrypt after reboot / after OS update.
- Actual DNS leak behaviour (system Private DNS, IPv6 DNS, captive portals).
- Per-app exclusion really bypasses the tunnel (needs `curl` from another app).
- Kill-switch card matches Android's real always-on/lockdown state.
- `data_extraction_rules` honoured by the device backup transport.
- ML Kit QR: camera permission denial path, malicious oversized QR payloads.
- SAF file import from cloud providers (slow/streamed `Uri`).
- Notification content never shows a secret (it shows profile name only — name is user-controlled, could contain anything).

Known gaps (not defects, documented): shred is not physically guaranteed on flash; sanitiser is pattern-based (a secret that looks like an ordinary word passes).

---

## 4. Architecture Audit

Gradle dependency graph (from `build.gradle.kts` files, verified by `tools/check-core-boundary.sh` → `OK: core boundary intact` at `bdcf860`):

```
app (main source set)
 ├── core:vpn ────────────► core:engine-api ──► core:model
 ├── core:config ─────────► core:engine-api, core:model
 ├── core:engine-api
 └── core:model

app (flavour "singbox", singboxImplementation)
 └── core:engine-singbox ─► core:singbox-config ─► core:engine-api, core:model
                          └► libs/libbox.aar  (io.nekohasekai.libbox, go.*)
```

Confirmed:
- `libbox` symbols appear **only** in `core/engine-singbox` (grep in CI + local).
- `io.ucc.core.singbox.*` is not imported from `core/model`, `core/engine-api`, `core/config`, `core/vpn`, or `app/src/main`.
- `app/src/singbox/` (flavour source set) is the sole place that names `SingBoxCoreFactory`.
- `core:vpn` depends on `engine-api` only (decoupled in `c2f3be0`).
- Connection Manager (`DefaultConnectionManager`) is the single source of truth; UI observes `state`/`events` only; settings enter through `StartOptionsProvider`.
- Boundary script runs as a CI step before tests.

Residual coupling worth noting (not a violation): `app` reads `VpnServiceRegistry.lockdownStatus` and `LockdownStatus` from `core:vpn` — an Android/VPN concept, not a core concept, so it is correctly placed.

---

## 5. Tests

Counted with `grep -c @Test` over `git ls-files '*Test.kt'` at `bdcf860`:

| Module | Test files | Tests |
|---|---|---|
| `core/config` | 8 | 87 |
| `core/engine-api` | 3 | 24 |
| `core/singbox-config` | 1 | 18 |
| `core/model` | 1 | 3 |
| `app` (JVM unit) | 14 | 80 |
| `app` (androidTest) | 0 | 0 |
| **Total** | 27 | **212** |

- **CI status:** run `35161540534` on `bdcf860` — `Build & test` success; `libbox` success (cached AAR, sha256 pinned).
- **Build status:** `assembleSingboxDebug` success. **Release variant is never built in CI.**
- **Lint status:** `lintSingboxDebug` 0 errors, 75 warnings (deprecated `TabRow`, `LocalLifecycleOwner`, unused resources, etc.).
- **Boundary check:** pass.
- **Known flaky tests:** none currently failing. Historically `DefaultConnectionManagerTest` disconnect test was flaky on `state` (fixed by observing `transitions`); `ServersViewModelTest` needed `advanceUntilIdle` (fixed). Manager tests use real `Dispatchers.Default` with 5 s timeouts — a slow runner could still time them out.
- **Tests that should exist but do not:**
  - `SubscriptionRefreshWorker` (constraints, policy, backoff).
  - `HttpSubscriptionFetcher` against a real local server (MockWebServer) incl. redirects to `http://`, size-cap streaming, `subscription-userinfo` header.
  - `UcVpnService.openTun` builder mapping (routes, excluded routes, DNS servers, allowed/disallowed apps) — needs Robolectric or instrumentation.
  - `AndroidNetworkMonitor` event mapping.
  - `SingBoxCoreAdapter` start/stop/event mapping with a fake libbox (currently untestable; would need an interface seam).
  - Instrumentation: import→preview→save UI flow; Settings persistence across process death; Keystore round-trip.
  - Golden test: generated sing-box JSON accepted by `libbox.checkConfig` (needs the AAR in a JVM-loadable form or an instrumented test).
  - `LogBuffer` capacity under concurrent emitters.
  - RTL snapshot tests.

---

## 6. Device Verification Checklist

All items require a **physical Android device** (P = physical only; E = emulator acceptable). Nothing here has been executed.

| # | Area | Steps | Expected | P/E |
|---|---|---|---|---|
| 1 | First launch | install debug APK, open | Home with empty state, no crash, fa/en follows system, RTL mirrored | E |
| 2 | Paste import | paste a `vless://` link | preview shows protocol/host/port, no secret; save; appears in Servers | E |
| 3 | Duplicate import | paste same link again | flagged duplicate by fingerprint, not saved twice | E |
| 4 | Clipboard | copy link, tap Clipboard | same as 2 | E |
| 5 | QR | scan a QR of a share link | preview; deny camera permission → graceful message | **P** |
| 6 | File | pick a `.txt` with 3 links + 1 bad line | 3 imported, 1 reported; >limit file rejected | E |
| 7 | Subscription | add https URL (base64 body) | group created; refresh updates; renamed profile keeps name after refresh | E |
| 8 | VPN permission | first Connect | system dialog; deny → `error_vpn_permission` + hint; allow → connects | E |
| 9 | Connect | Connect a working server | Connected, notification shown, stats moving, `curl ifconfig.me` = server IP | **P** (real network) |
| 10 | Disconnect | Disconnect | Disconnected, notification gone, real IP restored | **P** |
| 11 | Traffic | stream video 2 min | counters increase, no core restart in logs | **P** |
| 12 | DNS | set remote DNS `tls://9.9.9.9`; open dnsleaktest | resolver shown is Quad9 path, not ISP | **P** |
| 13 | DNS hijack | set device DNS manually to 8.8.8.8; connect | still resolved via configured remote DNS (core log) | **P** |
| 14 | Routing rules | `*.ir → Direct`, `keyword:ads → Block` | .ir site via real IP; ads host refused | **P** |
| 15 | Per-app | exclude browser | browser real IP, other apps proxied | **P** |
| 16 | Kill switch | enable Always-on + Block in Android; kill process | no traffic until service restarts; Settings card shows ON | **P** |
| 17 | Wi-Fi → mobile | switch while connected | Logs: NETWORK + RECONNECT; back to Connected < 10 s; no core restart | **P** |
| 18 | Mobile → Wi-Fi | reverse | same | **P** |
| 19 | Airplane mode | toggle on 30 s, off | Reconnecting(attempt 0) → Connected after network returns | **P** |
| 20 | Background | connect, leave app 30 min, screen off | tunnel alive; notification persists; no battery-optimisation kill | **P** |
| 21 | Reboot | connected + always-on; reboot | service restarts and reconnects last profile with current settings | **P** |
| 22 | Process kill | `adb shell am kill io.ucc.app.debug` with tunnel up | `START_STICKY` restart → reconnect | E |
| 23 | Corrupted storage | overwrite `files/profiles.enc` with junk | Home notice, quarantine file present, app usable, import works | E |
| 24 | Old-version migration | install build with plaintext `profiles.json`, upgrade | profiles present, `.json` gone, `.enc` present | E |
| 25 | Keystore | reboot, open app | profiles decrypt without user auth | **P** |
| 26 | Battery | enable battery saver + Doze (`adb shell dumpsys deviceidle force-idle`) | tunnel survives; WorkManager refresh deferred not lost | **P** |
| 27 | Log sanitiser | connect with known password; Share logs | password absent from exported text | E |
| 28 | Share/export | share 2 profiles | links re-import identically (fingerprint equal) | E |

---

## 7. Remaining Work (ranked by technical blocking status only)

### CRITICAL (nothing can be called a working VPN client until these are done)
1. Device verification of the core path: VPN permission → tunnel establish → traffic → disconnect (checklist #8–11). Everything downstream depends on this.
2. Android Keystore round-trip on a device (#25) — if this fails, all stored profiles are unreadable.
3. Release build actually compiled with R8 (`assembleSingboxRelease`) and smoke-tested — R8 has never run; libbox/Compose/serialization keep rules are unverified.

### HIGH (blocks release, not blocked by anything else)
4. Signing configuration (keystore from CI secrets), versioning scheme, release CI job producing APK + AAB.
5. Boot receiver or explicit documentation that reboot persistence relies solely on Android always-on (`RECEIVE_BOOT_COMPLETED` currently declared but unused → remove or implement).
6. Network-behaviour verification of Phase 5 features (#12–19) — otherwise these must ship labelled "unverified".
7. Missing tests listed in §5 for Worker, fetcher (MockWebServer), and TUN builder mapping.
8. Bundle full licence texts (GPLv3 for sing-box; ML Kit ToS) and a source-offer statement in About — GPL compliance for distribution.

### MEDIUM (product completeness; no release-blocking dependency)
9. ~~Smart selection~~ — done (Feature Completion Pass); remaining: device verification of Smart connect/failover on a real network.
10. Profile editing / detach from subscription; custom groups.
11. Xray/Clash JSON and WireGuard `.conf` import.
12. Per-connection log table (`CommandConnections`).
13. Reduce 75 lint warnings; migrate deprecated `TabRow`/`LocalLifecycleOwner`.
14. RTL visual review and screenshot tests.
15. Instrumentation test suite (`app/src/androidTest` is empty).

### OPTIONAL
16. Fake-IP DNS mode.
17. Rule-set (geosite/geoip) downloads.
18. Crash reporting (decision needed; none integrated — see §8).
19. Quick-settings tile, widget, shortcuts.

---

## 8. Release Readiness

| Item | State | Evidence |
|---|---|---|
| Release build | **Never built.** `release { isMinifyEnabled = true; isShrinkResources = true }` configured; CI only runs `assembleSingboxDebug` | `app/build.gradle.kts`, `android-ci.yml` |
| Signing | **Not configured.** Comment says "Phase 9 from CI secrets" | `app/build.gradle.kts` |
| R8 / ProGuard | Rules exist for kotlinx.serialization and `io.ucc.**` serializers; `engine-singbox/consumer-rules.pro` keeps `io.nekohasekai.libbox.**`, `go.**`. **Never executed** | files present |
| APK / AAB | Debug APK uploaded as CI artifact; no AAB, no release APK | CI artifacts |
| Versioning | `versionCode = 1`, `versionName = "0.1.0"`, hard-coded | `app/build.gradle.kts` |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (subtype `vpn`), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` (**unused**), `QUERY_ALL_PACKAGES` (per-app picker; Play requires a declaration form), `CAMERA` (optional feature) | manifests |
| Privacy policy | **None.** Required by Play for VPN apps and for `QUERY_ALL_PACKAGES`; not verified against Play policy | — |
| Third-party notices | `Notices` class renders engine + app notices; shown in Settings → About → licences dialog. Full licence texts **not** bundled | `Notices.kt`, `SettingsScreen.kt` |
| Licences | Project GPLv3 (`LICENSE`); sing-box GPLv3 + name/association clause; ML Kit proprietary ToS (bundled model). Legal review of the sing-box naming clause and ML Kit redistribution **not done** | `docs/CORE_LICENSE_AUDIT.md` |
| Crash reporting | **None integrated** (deliberate: no telemetry decision taken). | — |
| Logging | in-memory ring only; sanitised; no file/logcat persistence of secrets; core log level user-selectable (debug level may still be verbose on device) | `LogBuffer`, `LogSanitizer` |
| Backup behaviour | `allowBackup=false`, extraction rules exclude everything | manifest, xml |
| Play Store | **NOT VERIFIED.** Known requirements not yet met: privacy policy URL, VPN-service policy declaration, `QUERY_ALL_PACKAGES` justification, data-safety form, signed AAB, target API 36 OK, foreground-service `specialUse` justification text. No compliance claim is made. | — |

---

## 9. Core Decision (current, recorded)

- **sing-box (libbox `v1.13.21`, sha256-pinned AAR built in CI) remains the active and only Core.**
- **Xray-core is NOT implemented.** No Xray module, flavour, or config generator exists.
- `CoreFactory` / `CorePlatform` / `CoreCapabilities` / flavour-scoped wiring keep the boundary ready for a future engine; `tools/check-core-boundary.sh` enforces it in CI.
- **No Xray migration is to be started unless explicitly approved by the owner** (approved decision from the licensing audit; product is GPLv3, not proprietary).

---

## 10. Final Summary

### A. Genuinely finished (code + tests + CI)
Config engine for 10 protocols and sing-box JSON; import pipeline (paste/clipboard/file/subscription logic) with preview, dedupe, capability check; server management (favorites, search, rename, delete, share, subscription refresh with edit-preserving merge); encrypted storage with atomic writes, migration and quarantine; connection state machine with reconnect/backoff/network events; typed DNS/routing/per-app options and sing-box config generation; sanitised categorised logs; capability-gated Settings with honest kill-switch status; error classification and reachability diagnostics; Core boundary + CI enforcement; en/fa strings.

### B. Partially finished
QR and file import (UI/camera path build-verified only); WorkManager auto-refresh (worker untested); background/foreground service (never run); traffic statistics (never observed); reboot persistence (permission declared, no receiver); About/licences (dialog only, texts not bundled); Phase 6 diagnostics (smart selection missing); RTL (strings yes, layout unreviewed); security review (code-level only).

### C. Not started
Release signing/AAB/release CI; R8 execution; privacy policy; smart selection; profile editing; custom groups; Xray/Clash/`.conf` import; instrumentation tests; crash reporting decision; Fake-IP; rule-sets.

### D. Absolutely requires a physical Android device
Tunnel establishment and real traffic; DNS behaviour and leak testing; routing rules and per-app isolation in practice; kill-switch card vs. real lockdown; Wi-Fi/mobile/airplane transitions; reboot and Doze survival; Keystore after reboot; QR camera; battery behaviour.

### E. What blocks a release
1. No device verification of the fundamental VPN path (CRITICAL #1–2).
2. Release variant never built/signed; R8 never run (CRITICAL #3, HIGH #4).
3. GPL/ML Kit licence text bundling and source offer (HIGH #8).
4. Privacy policy + store declarations if distributing via Play.
5. Unused `RECEIVE_BOOT_COMPLETED` (either implement or remove) (HIGH #5).

### F. Next logical phase
**Device verification + release engineering (Phase 9 brought forward)**: execute §6 checklist #1–11 and #23–25 on a device first (they gate everything), then build/sign the release variant and run the same smoke tests on the R8 build. Phase 6 remainder (smart selection) and Phase 7 polish should wait until the tunnel has been proven on hardware — otherwise more features are stacked on an unverified foundation.

---
*Audit performed by Arena.ai Agent Mode. No production code changed; this document is the only new file.*

## Release Engineering pass (v1.0.0)

- versionName 1.0.0 / versionCode 1; release = R8 + resource shrinking; signing from env/`keystore.properties` only (unsigned when absent).
- App-level `RECEIVE_BOOT_COMPLETED` removed (no receiver). The merged manifest still carries it, blame-attributed to `androidx.work:work-runtime:2.10.5` (RescheduleReceiver re-arms the 12-hourly subscription refresh after reboot; WorkManager uses `setPersisted(false)`). Retained deliberately with an approval marker in `docs/RELEASE.md`; CI fails if it appears from any other source or without the marker. Latest green CI: run 35339938710 @ `6c467bf`. `QUERY_ALL_PACKAGES` kept — used by per-app routing; Play declaration required.
- New tooling: `tools/check-secrets.sh`, `tools/check-notices.sh`, `tools/inspect-release.sh`; CI builds and inspects APK+AAB.
- New docs: `RELEASE.md`, `PRIVACY_POLICY.md` (draft, placeholders), `DATA_SAFETY.md`, `PLAY_STORE_CHECKLIST.md`; `CORE_LICENSE_AUDIT.md` §6.
- Smart/reconnect audit: one race fixed (disconnect while a failover connect is queued) + 2 regression tests.
- Device verification of the minified release build: NOT AVAILABLE in this environment.
- First **signed** release run: CI 35360230312 @ `0e4ec8c` — `universal-connection-client-1.0.0-release-signed.apk`
  SHA-256 `2127f4572843838b488c87086e05cfd5d1ec2aecca03ec21c9d5648ae598e875`, apksigner `Verifies` (v2+v3),
  signer cert SHA-256 `629ef5f0…c5f12b` (CN=Ucc Test — a **test** key; replace secrets with the Play upload key before any store upload).
  Artifacts: app-release-signed 10555180725, app-bundle-signed 10555360630, r8-mapping 10555300736.

## KMP Phase 1 — foundation (DONE)

- `core/model`, `core/engine-api`, `core/config`, `core/smart`, `core/singbox-config` are Kotlin Multiplatform
  (`jvm()` target only for now); sources in `commonMain`/`commonTest`. New `core/platform` supplies sha256 / Base64 /
  percent-encoding / UUID / clock / IO dispatcher via expect-actual, with a JVM parity test against the `java.*`
  calls they replaced.
- JVM-only by design (`jvmMain`): `HttpSubscriptionFetcher`, `SocketDialer`, platform actuals.
- Public API changes: `CorePlatform.workingDirectory/cacheDirectory: File → String`; `TcpConnectionTester.SocketDialer`
  → top-level `io.ucc.core.smart.SocketDialer` (JVM); custom `Dialer`s report failures with `TcpConnectionTester.DialException`.
- Boundary check forbids `android.*`/`androidx.*`/`java.*`/`javax.*` in commonMain/commonTest. CI runs `jvmTest`
  explicitly and fails if any KMP module produced no results.
- Verified: CI 35392808121 @ `06ad1e7` — 394 tests, 0 failures (app 103×2 variants, config 90, smart 44, engine-api 24,
  singbox-config 18, model 3, platform 9); debug + signed release APK/AAB unchanged in identity, permissions, ABIs, R8.
- Not started: iOS targets/actuals (Phase 2).

## KMP Phase 2 — Apple targets & actuals (see docs/KMP_IOS.md)

- `iosArm64` + `iosSimulatorArm64` added to the six shared modules; iOS actuals for sha256 (CommonCrypto),
  UUID/clock (Foundation), IO dispatcher, `UrlSessionSubscriptionFetcher`, `NwConnectionDialer`.
- Verified: CI 35400107559 @ `fb5eb2b` — 24/24 Apple compile tasks (6 modules × main+test × 2 targets) succeeded on Linux
  (**compile verification only**; iOS test execution requires macOS — NOT RUN). JVM/Android in the same run: 394 tests / 0 failed,
  signed release APK/AAB unchanged in identity, permissions, signer and R8.
- No iOS app / NetworkExtension / Libbox / signing yet.

## KMP Phase 3 — shared application layer `core/app-logic` (see docs/KMP_APP_LOGIC.md)

- Import/Server/Subscription-refresh/Smart-coordination use-cases, ConnectionSettings, LogBuffer/LogSanitizer,
  AppLanguage, ProfilePreview and the Profile/Subscription/Selection/Settings store interfaces now live in the KMP
  module `core/app-logic` (jvm + iosArm64 + iosSimulatorArm64). Android keeps its encrypted-file/SharedPreferences
  implementations, ViewModels, Compose UI, WorkManager, core/vpn and core/engine-singbox unchanged.
- Verified: CI 35404168866 @ `6b335f0` — 339 tests / 0 failed (app 42×2 variants, app-logic 67, config 90, smart 44,
  engine-api 24, singbox-config 18, model 3, platform 9; 61 tests moved app→app-logic, 6 new); Apple compile 28/28
  tasks (klibs only, iOS tests NOT RUN on Linux); signed release APK/AAB unchanged in identity, permissions, signer, R8.

## KMP Phase 4 — iOS platform infrastructure `core/ios-infra` (see docs/KMP_IOS_INFRA.md)

Status: DONE (infrastructure only; no iOS UI, NetworkExtension, Libbox or macOS CI).
Verification run: 35412221054 on `5ed395b` — JVM/Android tests 365 passed / 0 failed
(`:core:ios-infra:jvmTest` 26/0), Android debug + release builds green, boundary/secret/notice
checks green, iosArm64 + iosSimulatorArm64 klib compile 32/32 tasks (8 modules).
iOS test EXECUTION: NOT RUN (Linux host). Device/simulator verification: NOT AVAILABLE.
Android storage code and formats unchanged; iOS envelope is `UCC2` (AES-256-CBC + HMAC-SHA-256,
CommonCrypto), JSON documents identical to Android.

## KMP Phase 5 — Apple Libbox build pipeline (see docs/LIBBOX_APPLE.md)

Scope: build + verify `Libbox.xcframework` from the pinned sing-box `v1.13.21`
(commit `628cb31ffa79cffffd34c2f9cde6cae044e4fc12`) on a macOS runner; no Network
Extension, no iOS UI, no signing/TestFlight. Files: `tools/build-libbox-apple.sh`,
`.github/workflows/libbox-apple.yml`, pins `core/engine-singbox/singbox.commit` and
`core/engine-singbox/libbox-apple.sha256`. Framework is not committed; it is a CI
artifact verified by SHA-256 + provenance. An Apple `CoreFactory`/`CoreAdapter`
module is deferred to Phase 6 because it needs the framework for cinterop and
cannot be compiled honestly on Linux. Android libbox.aar pin unchanged.
Verification: see the `libbox-apple-provenance` check-run on the commit recorded below.
Verification (Phase 5): libbox-apple run 35435865871 on `5c70f68` — macOS build success,
slices ios-arm64 + ios-arm64_x86_64-simulator validated, zip SHA-256
`618934255787d0ac61a3b8ed65f596b13589125eeacdf1314409abe7a9ddd781` (third distinct
hash for identical inputs → gomobile output not reproducible; pin left `unpinned`).
The artifact was NOT retained: the API lists 0 artifacts for the run (account
artifact storage quota). Android CI run 35438391052 on `c7d42a7`: 365 tests / 0
failed, debug + release builds, lint, boundary, secrets, notices green; Android
libbox.aar pin unchanged.

## KMP Phase 6 — iOS Network Extension foundation (see docs/IOS_NETWORK_EXTENSION.md)

Status: IMPLEMENTED (foundation; no Libbox, no UI). Module `core/ios-vpn`:
typed `TunnelConfiguration` → `NEPacketTunnelNetworkSettings`, versioned typed IPC
(`sendProviderMessage`), `TunnelSession` provider lifecycle, `UccPacketTunnelProvider`,
`NetworkExtensionVpnController`, `AppGroupStorage` (reuses Phase 4 stores),
`ExtensionTunnelHost`, `VpnError`. Declarative `ios/` entitlements + extension plist +
engine-bootstrap note. Only `TunnelEngine.None` exists: the provider fails
deterministically until Libbox is linked — nothing is faked.
Verification: JVM tests (commonTest) + iOS klib compile in Linux CI; Apple runtime
behaviour NOT verified (no device/simulator). Green Android CI run 35453968672 on cd0ca77: 398 JVM tests / 0 failed (33 in core/ios-vpn), iOS klib compile 36/36 tasks, Android debug+release built, signer unchanged.

**Phase 5 Libbox status:** macOS build green (5 successful builds, v1.13.21 @ 628cb31f),
but artifact retention blocked by the account Actions storage/billing limit —
`libbox-apple.sha256` remains `unpinned`; no further macOS runs are triggered.

**Remaining blockers:** (1) Actions artifact storage/billing → retained Libbox artifact +
pin; (2) macOS host with Xcode for framework export, Xcode project, device/simulator tests.
**Next phase (7):** Apple `CoreFactory`/`CoreAdapter` over Libbox (`TunnelEngine`
implementation), K/N framework export of `core/ios-vpn`, Xcode project wiring.

## KMP Phase 7 — Apple Libbox integration preparation (see docs/IOS_LIBBOX.md)

| Item | Status |
|---|---|
| Phase 6 (Network Extension foundation) | **VERIFIED** (JVM tests + iOS klib compile in CI; Apple runtime not available) |
| Phase 7 (Apple engine module `core/engine-singbox-apple`) | **PREPARED / IN PROGRESS** — adapter, engine, boundary, metadata, tests done; Libbox call bodies intentionally absent |
| Libbox runtime on Apple | **BLOCKED** — no retained `Libbox.xcframework` (Actions artifact storage/billing), no macOS host; `libbox-apple.sha256` = `unpinned` |
| Windows | **DEFERRED** to a future version |

Zero-cost mode: no macOS workflow triggered; Linux/Android CI only.

## Android v1.0.0 release closure (documentation pass, commit after 64563ba)

Code: release-ready at `64563ba` (CI 35463471779: 419/0 tests, signed release APK+AAB built — signed by the
**TEST** key `CN=Ucc Test`, not a production artefact). Prepared: privacy policy (publication candidate),
Data Safety answer sheet, Google Play release guide, corresponding-source plan, release checklist.
Remaining items are all owner/legal/Console actions (see `docs/ANDROID_V1_RELEASE_CHECKLIST.md`).
**Status: NOT READY — BLOCKERS REMAIN** (production key, hosted policy, GPL source availability, Play declarations,
retrievable final artefact). iOS → v1.1, Windows → v1.2. Tag `v1.0.0` deliberately not created yet.

## v1.0.1 backlog (approved by owner; NOT in v1.0.0)
Both behind Settings switches, default OFF, JVM-tested, sing-box 1.13.21 syntax only:
1. **TLS fragment** — per-outbound `tls.fragment: true`, `tls.record_fragment: true`, `tls.fragment_fallback_delay` (e.g. `"500ms"`); not for REALITY/Vision by default; documented caveats.
2. **Block QUIC** — `route.rules += { "protocol": "quic", "action": "reject" }` (no separate `udp/443` rule).
Not planned: Xray-style `fragment.length/interval` (rejected by Libbox), global `ipv4_only` (breaks IPv6-only networks), hardcoded DNS/server values.
