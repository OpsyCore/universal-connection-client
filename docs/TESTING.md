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
