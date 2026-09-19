# iOS Network Extension foundation (KMP Phase 6)

Module: **`core/ios-vpn`** (KMP: jvm for tests, iosArm64, iosSimulatorArm64).
Depends on `core/engine-api` (shared ports/state) and `core/ios-infra` (storage).
No Libbox, no UI, no signing. Android is untouched.

## Architecture: app ↔ extension

```
 Host app process                          │  Packet tunnel extension process (io.ucc.ios.tunnel)
 ─────────────────────────────────────────┼─────────────────────────────────────────────────────
 shared app-logic / ConnectionManager      │  UccPacketTunnelProvider : NEPacketTunnelProvider   (iosMain)
        ▲ attachRunningTunnel/disconnect   │        │ delegates every callback
 VpnStatusMirror  ◄── VpnStatus ──┐        │  TunnelSession (commonMain, JVM-tested)
 VpnController (interface)        │        │        ├─ parse(providerConfiguration)  → StartRequest
   └ NetworkExtensionVpnController ────────┼──────► ├─ start(): NetworkSettingsSpec → setTunnelNetworkSettings
       NETunnelProviderManager             │        │           → TunnelEngine.start()   (Phase 7: Libbox)
       load/save/remove/start/stop         │        ├─ stop(reason): engine.stop → STOPPED
       NEVPNStatusDidChangeNotification    │        └─ handle(bytes): IpcRequest → IpcResponse
 IpcClient ── sendProviderMessage ─────────┼──────► handleAppMessage
                                           │
 AppGroupStorage (both sides): App Group container files + App Group NSUserDefaults + Keychain access group
```

Same shape as Android: the core runs in the privileged tunnel process (VpnService ⇄
extension); the UI process only controls and observes it. There is **one** state
machine (`ConnectionManager` in engine-api); on iOS the host app mirrors the system
`NEVPNStatus` into it via the existing `attachRunningTunnel`/`disconnect` calls
(`VpnStatusMirror`), exactly how the Android app re-attaches to a running service.

## PacketTunnelProvider lifecycle (implemented)

| Callback | Behaviour |
|---|---|
| `startTunnel(options:)` | `TunnelSession.parse` the `providerConfiguration` (schema `ucc.schema=1`, `ucc.profileId`, `ucc.tunnelConfig` JSON) → typed `InvalidTunnelConfiguration` on any problem; `NetworkSettingsSpec.from(config)` → `setTunnelNetworkSettings`; then `TunnelEngine.start`. Success → `RUNNING`; any failure → `FAILED`, engine stopped, `NSError` (domain `io.ucc.iosvpn`, description = error code + non-sensitive detail). |
| `stopTunnel(with:)` | `NEProviderStopReason` → `StopReason`; if still `STARTING` the start is cancelled; engine stopped; always ends `STOPPED`; shutdown failure reported as `ProviderShutdownFailure` and via IPC `lastErrorCode`. |
| `handleAppMessage` | total handler: `Status`, `Statistics`, `Stop`, `Ping`; malformed frames answer `Error(MalformedResponse)`. |
| `sleep/wake` | pass-through (engine hooks arrive with Libbox). |

Provider states: `IDLE → STARTING → RUNNING → STOPPING → STOPPED`, `STARTING/RUNNING → FAILED`.
**`TunnelEngine.None`** is the only engine: `start` throws `ProviderStartupFailure("no tunnel
engine linked")`, so the provider can never report a running tunnel without Libbox.

## NETunnelProviderManager lifecycle (implemented, `NetworkExtensionVpnController`)

* `load()` — `loadAllFromPreferences`, selects the manager whose
  `providerBundleIdentifier == AppleTargetIds.tunnelBundleId`, subscribes to
  `NEVPNStatusDidChangeNotification` (push; no polling).
* `install(profileId, tunnel, description)` — create/update `NETunnelProviderProtocol`
  (`providerConfiguration = ProviderConfigKeys.build(...)`, `serverAddress` = tunnel anchor,
  never a proxy secret), `saveToPreferences` (first save triggers the system VPN permission
  dialog), then reload (Apple requirement before start).
* `uninstall()` — `removeFromPreferences`.
* `start(profileId)` — `startVPNTunnel(options:)`; typed `PermissionOrConfigurationUnavailable`
  / `ProviderUnavailable` / `ProviderStartupFailure`.
* `stop()` — `stopVPNTunnel()`.
* `status: StateFlow<VpnStatus>` (INVALID/DISCONNECTED/CONNECTING/CONNECTED/REASSERTING/DISCONNECTING).
* `ipc: IpcClient?` — null unless a session exists and status is not INVALID/DISCONNECTED.

## App Group boundary

`AppGroupStorage` is the single place that decides where ios-infra stores live:
`containerURLForSecurityApplicationGroupIdentifier(group.io.ucc.ios)/ucc` for the
encrypted files (`profiles.enc`, `subscriptions.enc`, `health.enc`), the App Group
`NSUserDefaults` suite for non-secret preferences, `KeychainKeys` for the file keys
(shared through the keychain access group). No store implementation is duplicated —
it instantiates `FileProfileStore`, `FileSubscriptionStore`, `FileServerHealthStore`,
`KeyValuePreferences`, `KeyValueSettingsStore` from Phase 4 with shared locations.
Secrets: only in Keychain / inside the `UCC2` encrypted files; never in UserDefaults and
never in `providerConfiguration` (which holds profile *id* + interface settings only).

Identifiers are centralised in `AppleTargetIds.Default` (`group.io.ucc.ios`,
`io.ucc.ios.tunnel`, `$(AppIdentifierPrefix)group.io.ucc.ios`); the entitlement files
under `ios/` carry the same literals and a test pins their consistency.

## IPC design

Frame: `[version byte = 1][UTF-8 JSON]`, JSON with `type` discriminator, ≤ 64 KiB.
Requests: `status`, `statistics`, `stop`, `ping(nonce)`. Responses: `status(state,
profileId, sinceEpochMs, lastErrorCode)`, `statistics(available, uplink, downlink)`,
`ack`, `pong(nonce)`, `error(code, detail)`.
`IpcClient` = `withTimeout` + deterministic classification (`VpnError.classify`):
`TimeoutCancellationException → IpcTimeout`, `CancellationException → IpcCancelled`,
decode failure/empty reply/wrong type → `MalformedResponse`, `sendProviderMessage`
rejected → `ProviderUnavailable`, anything else → `IpcTransport`. Error messages carry
class names and codes only.

## Configuration hand-off

`TunnelConfiguration` (typed, `@Serializable`, validated: MTU 576…65535, IPv4/IPv6
literals, prefix ranges, DNS as IP literals, proxy endpoint) is the Apple twin of the
shared `TunRequest` (`from()`/`toTunRequest()`; per-app package lists have no iOS
equivalent). `NetworkSettingsSpec.from(config)` computes exactly what goes into
`NEPacketTunnelNetworkSettings` (addresses + subnet masks, default route when
`autoRoute` and no explicit routes, excluded routes, DNS with `matchDomains=[""]`,
HTTP/HTTPS proxy) — framework-free and unit-tested; the iOS actual only copies fields.
The app puts the JSON into `providerConfiguration[ucc.tunnelConfig]`; nothing is hardcoded
in UI code and no server configuration is invented — values come from the core's
`TunRequest` once Libbox produces it.

## Network monitoring

Unchanged: `PathNetworkMonitor` (ios-infra, `nw_path_monitor`, push-based) serves both
processes; no extra polling was needed. `ExtensionTunnelHost` (iosMain) implements
`TunnelHost` for the extension: `acquire` returns a `TunProvider` whose `openTun` locates
the utun descriptor the system created and whose `protectSocket` is a no-op (NetworkExtension
already excludes provider sockets from the tunnel); `revoked` is emitted from `stopTunnel`.

## Error model

`VpnError`: `PermissionOrConfigurationUnavailable`, `ProviderUnavailable`,
`InvalidTunnelConfiguration`, `IpcTimeout`, `IpcCancelled`, `IpcTransport`,
`MalformedResponse`, `ProviderStartupFailure`, `ProviderShutdownFailure`; stable `code`,
`toConnectionError()` bridge to the shared family. Tests assert that details never
contain configuration values.

## Implemented / compile-only / blocked

| | |
|---|---|
| **Runtime-verified (JVM, Linux CI)** | TunnelConfiguration JSON + validation, NetworkSettingsSpec mapping, IPC codec (incl. malformed/oversized/wrong-version), IpcClient timeout/cancel/transport/malformed, VpnError classification + bridge, TunnelSession full lifecycle (start/stop/cancel-during-start/double-start/shutdown failure/IPC totality), VpnStatusMapper/Mirror, VpnController contract via fake, target-id consistency. |
| **Compile-only (iOS klib in Linux CI)** | `UccPacketTunnelProvider`, `NetworkExtensionVpnController`, `NEPacketTunnelNetworkSettings` mapping, `AppGroupStorage`, `ExtensionTunnelHost`/utun lookup. |
| **Not verified anywhere** | Behaviour on a device/simulator (permission prompt, status notifications, utun fd, App Group container). Requires Xcode + Apple runtime. |
| **Blocked by Libbox (Phase 7)** | A real `TunnelEngine` (Apple `CoreFactory`/`CoreAdapter` over Libbox), the K/N framework export of `:core:ios-vpn`, the Xcode project. Until then the extension starts and deterministically fails with `ProviderStartupFailure("no tunnel engine linked")`. |

## Required Apple entitlements / Xcode configuration (not validated on Linux)

Both targets (app + `io.ucc.ios.tunnel`), files provided under `ios/`:
* `com.apple.developer.networking.networkextension` = `[packet-tunnel-provider]`
  (needs the Network Extension capability on the App ID in the developer portal);
* `com.apple.security.application-groups` = `[group.io.ucc.ios]`;
* `keychain-access-groups` = `[$(AppIdentifierPrefix)group.io.ucc.ios]`.

Extension target: `NSExtensionPointIdentifier = com.apple.networkextension.packet-tunnel`,
`NSExtensionPrincipalClass = UccPacketTunnelProvider` (the Kotlin class itself — K/N
subclasses of Objective-C classes are final, so the engine is injected through
`TunnelEngineFactory.install` before instantiation; see `ios/PacketTunnel/TunnelEngineBootstrap.swift`), bundle id
`io.ucc.ios.tunnel`, embedded in the app. Provisioning profiles with these entitlements,
signing, TestFlight: later phases, not configured.
