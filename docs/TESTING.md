# Testing

## Tiers

| Tier | Where | What | Command |
|---|---|---|---|
| Sandbox | any machine w/o Maven access | Pure-Kotlin modules compiled with standalone kotlinc; tests run with a minimal reflective runner | `tools/local-check.sh` |
| CI unit | GitHub Actions | All modules; JUnit4 + kotlin-test; Robolectric for Android modules | `./gradlew test testDebugUnitTest` |
| CI build | GitHub Actions | `assembleDebug`, `lintDebug`, APK artifact | see `.github/workflows/android-ci.yml` |
| Device | physical device / emulator | Instrumentation tests + manual checklist below | `./gradlew connectedDebugAndroidTest` |

The sandbox tier is a convenience; **CI is authoritative**. `tools/local-check.sh`
uses stub JUnit annotations only so `kotlin.test` typealiases resolve; the
Gradle build uses the real `junit:junit`.

## Current coverage (Phase 1)

- `DefaultConnectionManagerTest` — 17 cases: happy path, missing profile,
  permission denied, core start failure, probe retry/exhaustion, disconnect,
  profile switch, same-profile no-op, network change reconnect, first network
  report ignored, network lost/restored, retryable & non-retryable core fatal,
  revocation, re-attach, backoff determinism.
- `SingBoxConfigGeneratorTest` — 16 cases: every protocol's outbound shape,
  Reality/uTLS, WS/gRPC/HTTP transports, WireGuard endpoint, full document
  (TUN/DNS/route), determinism, custom fragments, overrides, unsupported
  transport rejection, DNS server spec parsing, secret redaction.

## Device checklist (to run when a device is available)

1. Fresh install → Connect → system VPN consent dialog → tunnel up (key icon).
2. `curl https://ifconfig.me` from a terminal app shows the proxy's IP.
3. UDP: DNS over UDP inside a browser resolves; `nslookup` via a network tool.
4. IPv6: `https://test-ipv6.com` when the proxy supports it.
5. Toggle Wi-Fi ↔ mobile data: state shows *Reconnecting (n)* then *Connected*; traffic resumes.
6. Airplane mode on/off: *Reconnecting (0)* → *Connected*.
7. Kill the app from recents: notification stays, tunnel stays.
8. `adb shell am kill io.ucc.app.debug` (process death): service restarts and reconnects the last profile.
9. Revoke from Settings → VPN: app shows the *revoked* error, no zombie notification.
10. Lock screen 10 min: still connected, statistics continue.

Results must be recorded in the phase report; until then device verification
is reported as **NOT AVAILABLE**.

## Manual device checklist — Phase 5 networking (NOT yet executed)

Environment for this project has no device/emulator; the following must be run
by a human before any of these features is called verified.

| # | Feature | Steps | Expected |
|---|---|---|---|
| 1 | Routing DIRECT rule | add `*.ir → Direct`, connect, open an `.ir` site, check `core` logs | log shows `outbound: direct` for that host |
| 2 | Routing BLOCK rule | add `keyword:ads → Block`, open matching host | connection refused immediately; log `reject` |
| 3 | IP/CIDR rule | add `1.1.1.1 → Direct`, `curl 1.1.1.1` | direct in logs |
| 4 | Per-app include | include only a browser; use another app | other app has plain internet (not proxied) |
| 5 | Per-app exclude | exclude the browser | browser IP = real IP; others proxied |
| 6 | Remote DNS | set `tls://9.9.9.9`, resolve a name | `dig` via tunnel answered; leak test sites show the resolver, not ISP |
| 7 | DNS hijack | set device DNS to 8.8.8.8 manually; connect | queries still land at the configured remote DNS |
| 8 | IPv6 off | disable, connect, `test-ipv6.com` | no IPv6 connectivity, no long timeouts |
| 9 | Kill switch OFF card | connect, disconnect | traffic flows without VPN (expected; the card says OFF) |
| 10 | Kill switch ON | enable Always-on + Block in Android; kill app process | no traffic until service restarts; card shows ON |
| 11 | Wi-Fi → mobile | switch networks while connected | Logs: NETWORK event, RECONNECT event, state returns to Connected, no core restart |
| 12 | mobile → Wi-Fi | reverse of 11 | same |
| 13 | Process restart | `am kill` with tunnel up | service restarts, reconnects last profile with *current* settings |
| 14 | Log sanitiser | connect with a profile whose password is a known string; share logs | string absent from export |
