# Architecture

Status: Phase 1 (foundation). Updated as each phase lands.

## Layering

```
:app                    Compose UI · ViewModels · AppGraph (manual DI) · JSON profile store (temporary)
   │
:core:vpn               UcVpnService (foreground, owns TUN fd) · AndroidTunnelHost · AndroidNetworkMonitor
   │
:core:engine-singbox    SingBoxCoreAdapter (libbox CommandServer/Client) · AndroidPlatformInterface · LocalDnsTransport
   │
:core:singbox-config    SingBoxConfigGenerator  (ConnectionProfile → sing-box JSON, pure Kotlin)
   │
:core:engine-api        CoreAdapter · ConnectionState · ConnectionError · ConnectionManager (state machine, pure Kotlin)
   │
:core:model             ConnectionProfile · Protocol · Transport · TlsSettings · Authentication (pure Kotlin)
```

Rules:

- Pure-Kotlin modules (`model`, `engine-api`, `singbox-config`) have **no Android
  dependency** and use `explicitApi()`. They are compiled and unit-tested both by
  Gradle (CI) and by `tools/local-check.sh` (sandbox tier).
- UI and domain never import `io.nekohasekai.libbox.*`. Only `engine-singbox` does.
- Networking state is owned by exactly one object: `DefaultConnectionManager`.
  UI issues `connect(profileId)` / `disconnect()` and renders `state`.

## Connection state machine

See `ConnectionState.kt`. Transitions are serialized through a mutex in
`DefaultConnectionManager`; reactive inputs are:

| Input | Source | Effect |
|---|---|---|
| `connect(id)` | UI | `Disconnected/Error → Starting → Connecting → Connected` (or `Error`) |
| `disconnect()` | UI / notification action | `* → Stopping → Disconnected` |
| `NetworkEvent.DefaultChanged` | `AndroidNetworkMonitor` | If key changed while `Connected` → `Reconnecting(n)` loop (core `resetNetwork`, probe, backoff) |
| `NetworkEvent.Lost` | same | `Connected → Reconnecting(0)`; next `DefaultChanged` resumes the loop |
| `CoreEvent.Fatal(retryable)` | core adapter | `Reconnecting` loop with core restart |
| `CoreEvent.Fatal(non-retryable)` | core adapter | `Error` |
| `revoked` | `UcVpnService.onRevoke` | `Error(VpnRevoked)` |

"Connected" is only declared after a **real** HTTP probe through the tunnel
succeeds (`ReconnectPolicy.probeUrl`, default `gstatic generate_204`). There is
no optimistic connected state.

## Process & lifecycle

- `UcVpnService` runs in the main process (simplest, matches SFA/Hiddify). It
  is `START_STICKY` while a profile is active; on system restart with a null
  intent it re-issues `connect(lastProfileId)` through the manager — a genuine
  reconnect, not a restored flag.
- `VpnServiceRegistry` is the process-local rendezvous between the manager
  (which asks Android to start the service) and the service instance.
- The `CommandServer` (libbox gRPC over a unix socket in the sandbox) survives
  profile switches; only the inner box service is restarted.

## Statistics

`CommandClient` with `CommandStatus` at 1 s interval → `CoreStatistics`
(uplink/downlink rates and totals, connection counts, memory). Displayed as-is.
When the command client cannot connect, statistics show "—" — never zeros.

## Where things will go

| Phase | Module |
|---|---|
| 2 Config engine | `:core:config` (URI/JSON/subscription parsers → `ConnectionProfile`) + QR in `:app` |
| 4 Persistence | `:core:data` (Room + encrypted credentials + DataStore); replaces `JsonProfileStore` |
| 5 Smart engine | `:core:smart` (pure Kotlin scoring + Android probe runner) |
| 6 Routing/DNS | rule model in `:core:model`, generator support already present via `routingConfig`/`dnsConfig` |
