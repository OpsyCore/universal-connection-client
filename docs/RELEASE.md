# Release Engineering — v1.0.0

| | |
|---|---|
| applicationId | `io.ucc.app` (debug: `io.ucc.app.debug`, versionName suffix `-debug`) |
| versionName / versionCode | `1.0.3` / `4` (v1.0.2 = `1.0.2` / `3` — CI candidate only, never published; v1.0.1 = `1.0.1` / `2`, v1.0.0 = `1.0.0` / `1`) — bump both in `app/build.gradle.kts` for every store release |
| Flavour | `singbox` (only flavour; selects the engine module) |
| Core | sing-box / libbox `v1.13.21` (`core/engine-singbox/singbox.version`, SHA-256 pinned in `libbox.sha256`) |
| min / target / compile SDK | 24 / 36 / 36 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` (`ndk.abiFilters`; x86 32-bit deliberately excluded — no gomobile target and no real devices) |
| Minification | R8 full mode via AGP default, `isMinifyEnabled=true`, `isShrinkResources=true`, `proguard-android-optimize.txt` + `app/proguard-rules.pro` |

## Build commands

```bash
# prerequisites: JDK 17, Android SDK (build-tools for aapt2/apksigner), libbox.aar in core/engine-singbox/libs
#   (built by .github/workflows/libbox.yml or tools/build-libbox.sh — needs Go 1.25 + NDK r28)

./gradlew jvmTest test testSingboxDebugUnitTest  # unit tests (KMP jvm targets + app)
./gradlew :app:lintSingboxDebug                  # lint (errors fail the build)
tools/check-core-boundary.sh                     # engine isolation
tools/check-secrets.sh                           # secret scan (tracked files)
tools/check-notices.sh                           # licence/notice presence
./gradlew :app:assembleSingboxDebug              # debug APK
./gradlew :app:assembleSingboxRelease            # minified release APK
./gradlew :app:bundleSingboxRelease              # release AAB
tools/inspect-release.sh app/build/outputs/apk/singbox/release/*.apk app/build/outputs/bundle/singboxRelease/*.aab
```

Outputs:
- `app/build/outputs/apk/singbox/release/app-singbox-release.apk` (or `…-unsigned.apk` when no signing material)
- `app/build/outputs/bundle/singboxRelease/app-singbox-release.aab`
- `app/build/outputs/mapping/singboxRelease/mapping.txt` — **keep per release** (crash de-obfuscation); CI artifact `r8-mapping`
- `…/mapping/singboxRelease/resources.txt` — resource-shrinker report

## Signing

Nothing signing-related is committed (`.gitignore`: `*.jks`, `*.keystore`, `*.p12`, `app/keystore.properties`; `tools/check-secrets.sh` enforces it in CI).

`app/build.gradle.kts` resolves a release signing config from, in order:

1. **Environment variables** (CI):
   `UCC_KEYSTORE_FILE`, `UCC_KEYSTORE_PASSWORD`, `UCC_KEY_ALIAS`, `UCC_KEY_PASSWORD`
2. **Local file** `app/keystore.properties` (untracked):
   ```
   storeFile=/absolute/path/upload-key.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```
3. Otherwise: **unsigned** release (Gradle logs `ucc: no release signing material found`), file name carries `-unsigned`.

GitHub Actions secrets used by `.github/workflows/android-ci.yml`:
`UCC_KEYSTORE_BASE64` (base64 of the `.jks`), `UCC_KEYSTORE_PASSWORD`, `UCC_KEY_ALIAS`, `UCC_KEY_PASSWORD`.
The workflow decodes the keystore into `$RUNNER_TEMP`, builds, and deletes it. If the
secret is absent the job **does not fail**; artifacts are uploaded as
`app-release-UNSIGNED` / `app-bundle-UNSIGNED`. Signed runs upload `app-release-signed` / `app-bundle-signed`.
Signing schemes: v2 + v3 (v1 disabled; minSdk 24 does not need it).

Creating the upload key (once, offline, never in the repo):
```bash
keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```
Enrol in Play App Signing and register this as the **upload** key.

Configuring CI signing (repository admin, once):
```bash
gh secret set UCC_KEYSTORE_BASE64  -R OpsyCore/universal-connection-client < <(base64 -w0 upload-key.jks)
gh secret set UCC_KEYSTORE_PASSWORD -R OpsyCore/universal-connection-client   # prompts
gh secret set UCC_KEY_ALIAS         -R OpsyCore/universal-connection-client   # e.g. upload
gh secret set UCC_KEY_PASSWORD      -R OpsyCore/universal-connection-client
```
Then re-run the workflow. A signed run: names the files `universal-connection-client-1.0.0-release-signed.{apk,aab}`,
runs `apksigner verify --verbose --print-certs` and **fails** unless the result is `Verifies` with a v2 signature,
records the signer certificate DN/SHA-256 in `PROVENANCE.txt`, and uploads `app-release-signed` /
`app-bundle-signed` plus `r8-mapping` for that exact build. The SHA-256 of a signed APK differs from the unsigned
one (signature block appended); dex, resources and native libraries are byte-identical.

Smoke-test key vs. production key: the *same* mechanism serves both. If the physical smoke test must happen before
the production upload key exists, generate a dedicated **test** keystore with the command above, load it into the
secrets, run CI, test, then replace the secrets with the real upload key before the Play upload. Never reuse a test
key as the Play upload key.

## What R8 keeps and why (`app/proguard-rules.pro`, `core/engine-singbox/consumer-rules.pro`)
| rule | reason |
|---|---|
| `io.nekohasekai.libbox.**`, `go.**` | gomobile binding classes are resolved by name from Go via JNI |
| `io.ucc.**$$serializer`, `Companion.serializer()` | kotlinx.serialization generated serializers looked up by name; on-disk JSON formats (`profiles.enc`, `subscriptions.enc`, `health.enc`) must stay stable across upgrades |
| `SourceFile,LineNumberTable` | readable stack traces with the mapping file |
| VpnService / Activity / Worker | kept automatically from the manifest and WorkManager's own consumer rules — no extra rules |
| Compose / CameraX / ML Kit / AndroidX | consumer rules shipped in their AARs |

No blanket `-keep class ** { *; }`. `tools/inspect-release.sh` fails if the dex still
contains un-obfuscated `io.ucc.app.ui.*ScreenKt` names (evidence that R8 ran).

## Release path debug audit
- `BuildConfig.DEBUG` is used once: `AppGraph` passes `debug = BuildConfig.DEBUG` to `AndroidCorePlatform`, which only controls whether core log lines are mirrored to logcat (`SingBoxCoreAdapter`, `if (debug) Log.d`). Networking, encryption and validation do not depend on it.
- No localhost/dev endpoints, test credentials or test profiles in `src/main` (grep in CI secret scan covers credential-shaped URLs).
- Network security config forbids cleartext for app-originated HTTP.
- `android:debuggable` is false in release (verified by `tools/inspect-release.sh`).

## Permissions in the release manifest (merged)

Source of truth: the manifest-merger blame report
(`app/build/intermediates/manifest_merge_blame_file/singboxRelease/.../manifest-merger-blame-*-report.txt`),
printed by the CI step "Merged release manifest and permission blame" and enforced by `tools/inspect-release.sh`
(fails on any permission outside this table, on `AD_ID`, and on `RECEIVE_BOOT_COMPLETED` unless it is attributed
to `androidx.work` by the blame report *and* the approval marker below is present).

| permission | class | contributed by | runtime use in this app | Play / policy |
|---|---|---|---|---|
| `INTERNET` | required by app | `core/vpn` manifest | tunnel + proxy traffic, subscription fetch, health probes | normal |
| `ACCESS_NETWORK_STATE` | required by app (also declared by androidx.work) | `core/vpn`, `androidx.work` | default-network monitoring for reconnect / Smart transport stamping; WorkManager `NetworkType.CONNECTED` constraint | normal |
| `FOREGROUND_SERVICE` | required by app (also declared by androidx.work) | `core/vpn`, `androidx.work` | `UcVpnService.startForeground` | normal |
| `FOREGROUND_SERVICE_SPECIAL_USE` | required by app | `core/vpn` | FGS type `specialUse`, `<property PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn">` | **policy-sensitive**: FGS declaration + use-case review in Play Console |
| `POST_NOTIFICATIONS` | required by app | `core/vpn` | the persistent VPN notification (Android 13+) | runtime permission; normal |
| `CAMERA` | required by app | `app` manifest | QR scanner only; `uses-feature android.hardware.camera required=false` | runtime permission; normal |
| `QUERY_ALL_PACKAGES` | required by app | `app` manifest | per-app routing: `InstalledApps` enumerates launchable packages via `PackageManager.getInstalledApplications` so the user can include/exclude apps from the tunnel (`VpnService.Builder.addAllowedApplication/addDisallowedApplication`). A targeted `<queries>` filter cannot express "every app the user has", so the broad permission is genuinely needed. | **policy-sensitive**: Permissions Declaration Form required; allowed use case "device/app management / VPN per-app". Reviewer may reject; fallback would be removing per-app routing. |
| `RECEIVE_BOOT_COMPLETED` | required by dependency, also used by app (v1.0.1) | `androidx.work:work-runtime` (blame-attributed; no app module declares it) | (1) WorkManager's non-exported `RescheduleReceiver` re-arms the 12-hourly `SubscriptionRefreshWorker` after a reboot (jobs are `setPersisted(false)`). (2) **v1.0.1:** `core/vpn` `BootReceiver` (`exported="false"`, `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`) implements the default-OFF Settings switch "Connect on boot". It connects only if the switch is on **and** `VpnService.prepare()` is null (consent still granted) **and** a selected/last profile exists **and** the tunnel is not already active (`BootConnectPolicy`, JVM-tested); it never shows UI. The connect runs through the normal ConnectionManager → `startForegroundService` path (boot receiver = documented FGS-from-background exemption) and `UcVpnService` calls `startForeground()` at once. | normal permission; not a runtime prompt; not a Play declaration item. Boot auto-connect is opt-in. |
| `WAKE_LOCK` | required by dependency | `androidx.work` | keeps CPU awake while `SubscriptionRefreshWorker` runs | normal |
| `io.ucc.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | required by dependency | `androidx.core` | signature-level permission that protects `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` | app-private; normal |
| `com.google.android.gms.permission.AD_ID` | **removed** | would be merged by `play-services-basement` (ML Kit dependency) | none — no ads/analytics | stripped with `tools:node="remove"` in `app/src/main/AndroidManifest.xml`; inspection fails if it reappears |

Unused permissions in the merged release manifest: **none** (every entry above is either exercised by app code or by a library feature the app uses).

Explicit approval record (machine-checked by `tools/inspect-release.sh`):

`APPROVED-PERMISSION: android.permission.RECEIVE_BOOT_COMPLETED (androidx.work RescheduleReceiver)`

(The app-side `BootReceiver` relies on the same merged permission; if both subscription auto-refresh **and** "Connect on boot" are ever removed, delete this marker and add
`<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" tools:node="remove" />` to `app/src/main/AndroidManifest.xml`;
CI then expects `RECEIVE_BOOT_COMPLETED: ABSENT`.

## CI (`.github/workflows/android-ci.yml`)
Last verified CI run for this document: 35348184525 (commit `f437038`) — all steps green, artifacts unsigned. Signing secrets were configured afterwards; the first signed run is recorded in `docs/PROJECT_STATUS.md`.

libbox build (cached by tag) → boundary check → secret scan → notice check → unit tests →
debug APK → lint → **minified release APK + AAB** (signed only if secrets exist) →
`tools/inspect-release.sh` → upload `app-debug`, `app-release-*`, `app-bundle-*`, `r8-mapping`, `reports`.
Instrumentation tests: none configured (no emulator job).

## Release smoke test (physical device, minified release APK)
See `docs/DEVICE_TEST_PLAN.md` for the full matrix. Minimum before publishing:
install over previous build → existing profiles visible → connect/disconnect →
DNS resolves through tunnel → Smart connect picks a server → Test all → import QR (camera + gallery) →
subscription refresh → Persian RTL → About shows `<versionName> · <versionCode> · release · singbox` (v1.0.1 = `1.0.1 · 2`).
**Status for v1.0.0: PASSED on a real device (owner report, 2026-09-23; incl. section E DNS with IPv6 on/off). Not reproducible from the build environment.**

## v1.0.0 published
Tag `v1.0.0` = `875b954fc3d6c31063734471a0cbaef4747f544a`; release https://github.com/OpsyCore/universal-connection-client/releases/tag/v1.0.0 (assets + SHA-256 + signer cert in the release notes). Signed builds are delivered via a CI-created *draft* release because Actions artifact storage is quota-blocked for the account; the CI step never modifies a published release.

## Release closure documents (v1.0.0)
`docs/ANDROID_V1_RELEASE_CHECKLIST.md` (status board + upload-key generation incl. Termux + policy hosting),
`docs/GOOGLE_PLAY_RELEASE.md` (Console sections with factual justifications), `docs/DATA_SAFETY.md`,
`docs/PRIVACY_POLICY.md` (publication candidate; 3 publisher fields), `docs/SOURCE_RELEASE.md` (GPL corresponding source).
The CI signing key at the time of writing is `CN=Ucc Test` — **test only**; the store build must be signed by the
production upload key (checklist §Signing).

## Known blockers (as of this pass)
1. No signing key provisioned → CI artifacts are unsigned.
2. Device smoke test of the minified build not performed.
3. Privacy policy has publisher placeholders; not hosted.
4. Repo private → GPL corresponding-source offer not yet satisfied for public distribution.
5. Play declarations (QUERY_ALL_PACKAGES, VPN, specialUse FGS, Data Safety) are human actions.

## Legal review items
GPL-3.0 distribution obligations and derivative-work analysis for the libbox linking model;
sing-box additional naming clause; ML Kit ToS vs GPL distribution; privacy policy text.
Details: `docs/CORE_LICENSE_AUDIT.md`.

## Future Work (not implemented — no feature creep in the release pass)
- Runtime `POST_NOTIFICATIONS` prompt on Android 13+.
- Launcher-only `<queries>` fallback if the QUERY_ALL_PACKAGES declaration is rejected.
- "Reset all data" control in Settings.
- Store assets (512 px icon, feature graphic, screenshots).
