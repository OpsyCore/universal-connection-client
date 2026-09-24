# Apple Libbox integration (KMP Phase 7 — prepared, runtime BLOCKED)

Module: **`core/engine-singbox-apple`** (jvm for tests, iosArm64, iosSimulatorArm64).
Depends on `core/engine-api`, `core/singbox-config`, `core/ios-vpn`. Nothing depends on it
yet — it is wired only by the (future) Xcode packet-tunnel target. Android untouched.

## Architecture

```
UccPacketTunnelProvider (core/ios-vpn iosMain)
   └─ TunnelEngineFactory.create(TunnelEngineContext(tunnelHost))
        └─ AppleTunnelEngineBootstrap (engine-singbox-apple iosMain)       ← install() once at extension start
             └─ AppleSingBoxTunnelEngine : TunnelEngine        (commonMain, JVM-tested)
                  ├─ ProfileProvider   = AppGroupStorage.profiles     (ios-infra FileProfileStore, App Group)
                  ├─ StartOptionsProvider = AppGroupStorage.settings → ConnectionSettings.toStartOptions
                  ├─ TunnelHost        = ExtensionTunnelHost (utun fd)
                  └─ CoreAdapter       = AppleSingBoxCoreAdapter   (commonMain, JVM-tested)
                       ├─ SingBoxConfigGenerator (core/singbox-config — the ONE generator)
                       └─ LibboxServiceFactory / LibboxService   ← boundary
                            └─ AppleLibboxServiceFactory (iosMain)  ← THE integration point → Libbox.xcframework → sing-box
```

Same sequence as the device-verified Android `SingBoxCoreAdapter`: setup (once) → generate
JSON → create service (once, reused across restarts) → `checkConfig` → `startOrReload` →
running; `stop` = `closeService`; `shutdown` = `close`.

## Libbox boundary (`LibboxBoundary.kt`, commonMain)

Only what the Android adapter is verified to use against sing-box v1.13.21:

| Boundary | Libbox call it stands for (Android-verified) |
|---|---|
| `LibboxServiceFactory.setup(LibboxSetup)` | `Libbox.setup(basePath, workingPath, tempPath, debug)` |
| `LibboxServiceFactory.create(listener, tun)` | `CommandServer(handler, PlatformInterface).start()`; `PlatformInterface.openTun → TunProvider.openTun` |
| `LibboxService.checkConfig(json)` | `Libbox.checkConfig` |
| `LibboxService.startOrReload(json)` | `CommandServer.startOrReloadService(config, OverrideOptions())` |
| `LibboxService.closeService()` / `close()` | `CommandServer.closeService()` / `close()` |
| `LibboxListener.onServiceStopped/onStatus/onLog` | `CommandServerHandler.serviceStop`, `CommandClientHandler.writeStatus/writeLogs` |

No Libbox symbol is named in Kotlin anywhere; Apple gomobile signatures differ from the
Android ones (Objective-C naming, `NSError**` out-params) and **cannot be verified without
the framework**, so they were deliberately not written. `AppleLibboxServiceFactory`
(iosMain) is the single file allowed to import the cinterop; today it returns
`LibboxAvailability.Unavailable` and throws `LibboxUnavailableException` from `setup`/`create`.

## Exact integration point

`core/engine-singbox-apple/src/iosMain/kotlin/io/ucc/core/singbox/apple/AppleLibboxServiceFactory.kt`
— replace the two throwing bodies with the real calls, add the cinterop (`.def` or
CocoaPods) for `Libbox.xcframework`, set `LibboxMetadata.xcframeworkSha256` **and**
`core/engine-singbox/libbox-apple.sha256` to the hash of the retained artifact (a JVM test
keeps them equal). The boundary check then requires the pin to be 64-hex.

## Version / metadata (`LibboxMetadata`)

| | |
|---|---|
| sing-box | `v1.13.21`, commit `628cb31ffa79cffffd34c2f9cde6cae044e4fc12` (equal to `core/engine-singbox/singbox.*`, test-enforced) |
| gomobile | `v0.1.12` (SagerNet fork) |
| framework | `Libbox.xcframework`, expected slices `ios-arm64`, `ios-arm64_x86_64-simulator` |
| SHA-256 | **UNRESOLVED / `unpinned`** — `LibboxMetadata.xcframeworkSha256 = null`. Never guessed. |

## Deterministic behaviour without Libbox

`start` → `AppleSingBoxCoreAdapter.start` → `libbox.setup` throws → `CoreException(CoreFailure
("Libbox unavailable: …"))` → engine cleans up (adapter.stop, tunnelHost.release, state
STOPPED) → `ProviderStartupFailure("CoreFailure: Libbox unavailable …")` → `TunnelSession`
FAILED → `startTunnel` completion gets an `NSError`. If the bootstrap is never installed the
Phase 6 `TunnelEngine.None` still answers `"no tunnel engine linked"`. No path reports RUNNING.

## Lifecycle hardening (JVM-tested)

`AppleSingBoxTunnelEngine` states `IDLE → STARTING → RUNNING → STOPPING → STOPPED`,
`TERMINATED`. Duplicate start while STARTING/RUNNING rejected without touching the core;
stop-during-start sets a flag, start observes it before and after the core call and cleans
up itself (no orphaned core); start after stop reuses the single service; shutdown failure
reported as `ProviderShutdownFailure` with the state still settling to STOPPED; `terminate`
(called from `stopTunnel`, i.e. provider termination) closes the service and blocks restart.
`UccPacketTunnelProvider.stopTunnel` now also emits `TunnelHost.revoked` and calls
`engine.terminate()`.

## Configuration hand-off

Profile (App Group encrypted store) → `ConnectionSettings.toStartOptions` (+ MTU from the
provider's `TunnelConfiguration`) → `SingBoxConfigGenerator.generate` → `checkConfig` →
`startOrReload`. Secrets exist only in the generated JSON given to the core; exceptions,
events, logs and IPC carry redacted/classified text only (tests assert the credential never
appears).

## Status

| Implemented (JVM-verified) | `LibboxBoundary`, `LibboxMetadata`, `AppleSingBoxCoreAdapter`, `AppleSingBoxCoreFactory`, `AppleSingBoxTunnelEngine`; 25 tests incl. pin-file consistency |
| Compile-only (iOS klib, Linux) | `AppleLibboxServiceFactory` (Unavailable), `AppleTunnelEngineBootstrap`, provider `terminate` wiring |
| Not implemented | any call into Libbox; `onNetworkChanged/onPause/onResume/urlTest` bodies (need the linked core); K/N framework export; Xcode project |
| Requires Mac/Xcode | building the K/N framework, the Xcode targets, simulator/device runs, signing |
| Requires Libbox.xcframework | everything in `AppleLibboxServiceFactory`, the `.def`/pod cinterop, the SHA-256 pin |

**Exact blocker:** no retained `Libbox.xcframework` — GitHub Actions artifact storage is
rejected account-wide (billing/spending limit) and there is no macOS host/VPS to build or
host it. No macOS workflow was or will be triggered under zero-cost mode.
