# KMP Apple targets (Phase 2 — targets & actuals only)

Scope of this phase: the six shared modules (`core/platform`, `core/model`, `core/engine-api`,
`core/config`, `core/smart`, `core/singbox-config`) declare `iosArm64()` and `iosSimulatorArm64()`
next to `jvm()`. **No iOS app, NetworkExtension, Libbox, UI, signing or distribution exists yet.**

## Source-set layout

| source set | content |
|---|---|
| `commonMain` / `commonTest` | all business/config logic and all shared tests (no `android.*`, `androidx.*`, `java.*`, `javax.*`, `kotlin.jvm.*`, `platform.*`) |
| `jvmMain` / `jvmTest` | `HttpSubscriptionFetcher` (HttpURLConnection), `SocketDialer` (java.net), JVM actuals |
| `iosMain` / `iosTest` | Apple actuals below; **only** `platform.*` / `kotlinx.cinterop.*` may appear here |

## iOS actuals

| abstraction (commonMain) | iOS implementation | notes |
|---|---|---|
| `sha256(ByteArray)` | `platform.CoreCrypto.CC_SHA256` | 32-byte digest, identical to `MessageDigest("SHA-256")`; hex via shared `toHexLower()` |
| `randomUuidString()` | `NSUUID().UUIDString.lowercase()` | NSUUID emits upper-case; lower-cased to match `java.util.UUID.toString()` |
| `currentTimeMillis()` | `NSDate().timeIntervalSince1970 * 1000` (truncated) | epoch milliseconds |
| `platformIoDispatcher()` | `kotlinx.coroutines.Dispatchers.IO` | provided by kotlinx-coroutines for Kotlin/Native |
| `SubscriptionFetcher` | `UrlSessionSubscriptionFetcher` (`core/config` iosMain) | https-only (also on redirect), UA/Accept headers, Content-Length + streamed body limit → `TooLarge`, non-2xx → `Http`, transport → `Network`; headers interpreted by the shared `SubscriptionHeaders` |
| `TcpConnectionTester.Dialer` | `NwConnectionDialer` (`core/smart` iosMain) | Network.framework `nw_connection`, plain TCP; DNS domain → `DNS_FAILURE`, `ECONNREFUSED/ECONNRESET` → `CONNECTION_REFUSED`, `ETIMEDOUT`/deadline → `TIMEOUT`, `E{NET,HOST}{UNREACH,DOWN}` → `NETWORK_UNAVAILABLE`, else `UNKNOWN`. UDP-only protocols still short-circuit to `UNSUPPORTED` in common code |

## Verification levels (be precise when reporting)

* **Compile verification** — Linux CI job *Apple KMP compile* cross-compiles main + test klibs
  for both targets. Kotlin ≥ 2.2.20 does this by default (no `kotlin.native.enableKlibsCrossCompilation`
  needed; the property became a no-op) as long as no cinterop/CocoaPods is involved — and none is:
  the actuals only use the platform libraries (`Foundation`, `CoreCrypto`, `Network`, `darwin`, `posix`)
  shipped inside the Kotlin/Native distribution.
* **Test execution** — `iosSimulatorArm64Test` needs a macOS host with Xcode and is **not run** by CI.
  `commonTest` runs on the JVM (`jvmTest`) on every push; the `iosTest` sources are compiled only.

## Toolchain limitations

* Linking final binaries (frameworks, test executables) for Apple targets is impossible on Linux.
* cinterop `.def` files are forbidden in core modules by `tools/check-core-boundary.sh`; adding one
  would silently downgrade CI to "skipped" for Apple targets.
* Kotlin 2.3.20 has a known regression with `nw_parameters_create_secure_tcp` block arguments
  (KT-85508); `NwConnectionDialer` deliberately uses `nw_parameters_create()` + explicit TCP options
  instead of the block-based API.
