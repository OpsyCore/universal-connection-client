# PROJECT_AUDIT.md — Universal Connection Client (Android)

Audit date: 2026-09-16
Branch: `arena/01a0aa91-universal-connection-client` (from `main` @ `4e4fe74`)
Auditor: Lead Android Network Engineer (Arena agent)

---

## 1. Repository status

| Item | Finding |
|---|---|
| Commits on `main` | 1 (`4e4fe74 Initial commit`) |
| Tracked files | **1** — `.gitignore` (standard Android Studio template) |
| Project type | **None yet.** No Gradle project, no modules, no sources |
| AGP / Kotlin / Gradle versions | **Not defined** (no build files exist) |
| minSdk / targetSdk / compileSdk | **Not defined** |
| Modules / packages | None |
| UI | None |
| Networking / VPN service | None |
| Dependencies | None |
| Tests | None |
| CI | None (added a toolchain-probe workflow during this audit — see §5) |
| Resources | None |
| Architecture | None |
| Technical debt | None in code; the entire product is missing |

Search for `TODO`, `FIXME`, `placeholder`, `mock`, `fake`, `stub`, `hardcoded`,
`unfinished`, `disabled`, `temporary` across the repository: **0 matches**
(there is no code to match against).

Conclusion: this is a **greenfield** repository. There is nothing to preserve
except Git history and the `.gitignore`. There is nothing to "fix" before
building; there is nothing to build.

---

## 2. Build status

**No build exists.** `./gradlew assembleDebug` cannot be run because there is no
Gradle wrapper, no `settings.gradle(.kts)`, and no module.

### 2.1 Build environment constraints (important — read this)

The sandbox in which this agent operates was probed exhaustively. Results:

| Resource | Reachable from sandbox? |
|---|---|
| `github.com`, `api.github.com`, `codeload.github.com` | ✅ |
| `registry.npmjs.org`, `pypi.org` | ✅ |
| `services.gradle.org` (Gradle distributions) | ❌ TLS reset |
| `repo.maven.apache.org` / `repo1.maven.org` (Maven Central) | ❌ |
| `dl.google.com` / `maven.google.com` (Android SDK, AndroidX, AGP) | ❌ |
| `plugins.gradle.org` | ❌ |
| `go.dev` / `proxy.golang.org` (Go toolchain, for sing-box) | ❌ |
| GitHub release asset CDN (`release-assets.githubusercontent.com`) | ❌ |
| Debian apt mirrors | ❌ |
| Pre-installed JDK / Android SDK / Gradle | ❌ none |

What *was* obtained, using only reachable sources:

- **JRE 25 (Temurin)** via the `jdk4py` PyPI wheel — a runtime only (no `javac`,
  which is fine because Kotlin does not need it).
- **Kotlin compiler 2.4.20** (with `kotlin-stdlib`, `kotlinx-coroutines-core-jvm`,
  `kotlin-test`, serialization plugin) via the `kotlin-compiler` npm package.
  Verified: compiles and runs a coroutine sample.
- **`android-35.jar`** (compile-time stubs for API 35) from the
  `Sable/android-platforms` GitHub repo via the API. Verified to contain
  `android.net.VpnService`, `ConnectivityManager`, etc.
- **Gradle wrapper jar + `gradlew` script** from `gradle/gradle` tag `v8.13.0`
  (the wrapper cannot download a distribution from here, but it is what the
  repository needs to ship anyway).

What could **not** be obtained locally: a Gradle distribution, AGP, AndroidX,
Compose, JUnit jars (all GitHub copies found were LFS pointers), Go, NDK.

### 2.2 Consequence: two-tier verification model

1. **Local (sandbox) tier** — pure-Kotlin/JVM modules (config engine, models,
   ranking, routing rules, subscription merge) are compiled with `kotlinc`
   against `kotlin-stdlib` + `android-35.jar` and unit-tested with
   `kotlin-test` through a small script (`tools/local-check.sh`). This gives
   fast, real compile + test feedback for everything that does not need
   AndroidX.
2. **CI tier (GitHub Actions)** — the authoritative build. The `ubuntu-latest`
   runner has a full Android SDK, JDK 17, Go, and unrestricted access to Maven
   Central / Google Maven. `./gradlew assembleDebug`, `testDebugUnitTest`,
   `lint`, and (later) the sing-box `libbox.aar` gomobile build all run there.
   A probe workflow was pushed and **ran green** (job 104835237335) to confirm
   Actions are enabled for this private repo and the branch can be pushed.

**Every "Build: PASS" claim in later phase reports will cite a CI run ID.**
If CI is not green, the phase is not complete. No local-only "it compiles"
claim will be presented as an APK build.

Real-device / emulator testing: **NOT AVAILABLE** in this sandbox (no
KVM/emulator, no ADB device). Device verification will be reported honestly
as `NOT AVAILABLE` until a device is provided; the Android-only layers
(VpnService lifecycle, TUN) will be covered by instrumentation tests runnable
on a device plus CI-side Robolectric where meaningful.

---

## 3. Existing architecture

None. The recommended architecture is in §7.

---

## 4. Dependency inventory

Nothing declared. Planned inventory (to be pinned in `gradle/libs.versions.toml`):

| Area | Choice | Rationale |
|---|---|---|
| Build | AGP **8.13.x**, Gradle **8.14**, JDK 17 | Last stable AGP 8 line; avoids AGP 9's DSL/built-in-Kotlin migration churn on a brand-new project while remaining fully supported. Upgrade path to 9.x is documented. |
| Language | Kotlin **2.2.x** + Compose compiler plugin | K2, matches AGP 8.13 R8 support |
| UI | Jetpack Compose (BOM), Material 3, Navigation | Requirement |
| Async | kotlinx-coroutines, StateFlow | Requirement |
| Serialization | kotlinx-serialization-json | Config engine, subscription parsing, sing-box JSON generation |
| Persistence | Room (profiles, groups, subscriptions, history, health stats) + DataStore (settings) | Persistent identities, queries, migrations |
| Secrets | Android Keystore-backed AES-GCM for credential fields at rest | Requirement §18 |
| HTTP | OkHttp (subscription fetch, health probes) | Mature, proxy-aware |
| QR | CameraX + ML Kit barcode scanning (Google Play services-free "bundled" artifact) | Camera → detect → validate flow |
| DI | Manual DI via an `AppGraph` (no Hilt initially) | Justified: keeps build simple, no kapt/KSP; revisit if graph grows |
| Core | **sing-box** via `libbox.aar` (gomobile), built in CI | See `docs/CORE_DECISION.md` |
| Tests | JUnit4, kotlin-test, Turbine, Robolectric, Compose UI test | Requirement §20 |

---

## 5. CI status

`.github/workflows/android-ci.yml` (probe) — green. It will be replaced by a
real pipeline in Phase 1: `assembleDebug`, unit tests, lint, artifact upload,
and a separate cached job that builds `libbox.aar` from a pinned sing-box tag.

---

## 6. Risk assessment

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | **Core licensing.** sing-box is GPLv3 *plus* a clause forbidding derivative works from using its name or implying association. Embedding it makes the app a GPLv3 derivative → the app must be released under GPLv3 (as Hiddify/NekoBox/SFA are). Xray-core is MPL-2.0 (file-level copyleft). | High (product decision) | Proceed under GPLv3 for the app, use our own name/branding, never present as "sing-box". **Flagged for owner confirmation** — if a proprietary license is required, the core strategy must change (Xray-only, MPL) with reduced protocol coverage. |
| R2 | Sandbox cannot run Gradle/AGP; builds are CI-only | High (process) | Two-tier verification (§2.2); every phase cites a CI run. |
| R3 | No device/emulator | High (verification) | Honest `NOT AVAILABLE`; instrumentation tests written and documented for the owner to run; Robolectric for lifecycle logic. |
| R4 | libbox must be built from source with gomobile (no official Maven artifact) | Medium | CI job with pinned sing-box tag + Go + NDK, output cached and attached as artifact; checksum recorded. |
| R5 | Android VPN semantics (always-on, kill switch, per-app) vary by OEM/API | Medium | Use platform APIs only where available (`API ≥ 29` for `setMetered`, lockdown via system settings), never fake unsupported features. |
| R6 | Scope is very large | Medium | Strict phase gating; no feature is shown in UI until the layer beneath it works. |
| R7 | Sensitive data (UUIDs, passwords, PSKs) in profiles | Medium | Encrypted-at-rest credential fields, redaction in logs, no analytics. |

---

## 7. Recommended architecture

```
:app                     Compose UI, navigation, ViewModels, Android glue
:core:model              ConnectionProfile & friends (pure Kotlin, no Android)
:core:config             Detect → parse → validate → normalize (pure Kotlin)
:core:engine-api         CoreAdapter interface, ConnectionState, events (pure Kotlin)
:core:engine-singbox     sing-box JSON generation + libbox binding (Android)
:core:smart              health checks, ranking, failover policy (pure Kotlin)
:core:data               Room/DataStore repositories, subscription engine (Android)
:core:vpn                VpnService, TUN setup, ConnectionManager, network monitor (Android)
```

Pure-Kotlin modules are verifiable in the sandbox; Android modules are verified
in CI. UI depends only on `engine-api`/domain types — never on sing-box types.

---

## 8. Migration requirements

None (greenfield). The only inherited artifact is `.gitignore`, which is kept.

---

## 9. Proposed phase plan (unchanged from the mandate, with verification notes)

| Phase | Deliverable | Verification |
|---|---|---|
| 0 | This audit, `docs/CORE_DECISION.md` | — |
| 1 | Gradle multi-module skeleton, version catalog, CI pipeline, `engine-api`, VpnService foundation, ConnectionManager state machine, libbox CI build | CI green; state-machine unit tests |
| 2 | Config engine: URI/JSON/subscription parsing, validation, normalization, QR flow | Local + CI unit tests with real-world sample links |
| 3 | Connection engine: connect/disconnect/reconnect, network transitions, error classes | Unit + Robolectric; instrumentation tests written |
| 4 | Room persistence, groups, favorites, subscriptions, merge strategy | Unit tests on merge; Room migration tests |
| 5 | Smart engine: probes, scoring, cooldown, failover | Deterministic unit tests |
| 6 | Routing rules, split tunnel, per-app, DNS, kill switch | Config-generation tests; device tests documented |
| 7 | Production UI, fa/en, RTL, themes | Compose UI tests |
| 8 | Hardening, security review, perf | Lint, leak checks, log audit |
| 9 | Release: R8 rules, signing config, versioning, AAB | CI release job |

---

## 10. Files to be created/changed first (Phase 1)

- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`
- `app/` (manifest, Application, minimal Compose shell wired to ConnectionManager state)
- `core/model`, `core/engine-api`, `core/vpn` (VpnService + ConnectionManager)
- `core/engine-singbox` (adapter + libbox binding behind an interface; JNI-free)
- `.github/workflows/android-ci.yml` (real pipeline) and `.github/workflows/libbox.yml`
- `docs/ARCHITECTURE.md`, `docs/CORE_DECISION.md`, `docs/TESTING.md`
- `tools/local-check.sh` (sandbox-tier compile + test)
