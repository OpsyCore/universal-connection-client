# Settings (Phase 5)

Settings live in `app/.../data/ConnectionSettings.kt` and are persisted as JSON in
`SharedPreferences` (`connection_settings_v1`). They contain **no secrets** (no
credentials, no server addresses), so they are intentionally not encrypted.

## How settings reach the core

`ConnectionSettings.toStartOptions(capabilities)` produces `CoreStartOptions`
(engine-api). `DefaultConnectionManager` has a `StartOptionsProvider` port that is
consulted on **every** connect that does not pass explicit options — UI connect,
reconnect after network change, and the VpnService "always-on" restart. There is
therefore no path where stale settings are applied.

Settings changed while the tunnel is running apply on the **next** connect; the UI
says so explicitly. There is no live re-configuration.

| Setting | `CoreStartOptions` | sing-box mapping (`SingBoxConfigGenerator`) |
|---|---|---|
| Remote DNS | `remoteDns` | `dns.servers[remote]`, `detour = proxy`. A profile's own `dns.remoteDns` wins. |
| Direct DNS | `directDns` | `dns.servers[direct]`; empty → `local` (system resolver). |
| Bypass local network | `bypassPrivate` | `route.rules += { ip_is_private: true, outbound: direct }` |
| Strict routing | `strictRoute` | `inbounds[tun].strict_route` |
| IPv6 | `ipv6` | tun `inet6_address` / `dns strategy` |
| MTU | `mtu` (1280–9000) | `inbounds[tun].mtu` |
| Per-app mode + packages | `includePackages` / `excludePackages` | `inbounds[tun].include_package` / `exclude_package` — emitted **only** if `capabilities.perAppRouting` |
| Core log level | `logLevel` | `log.level` |

Unsupported options are not rendered: the per-app section is hidden when the
active core does not report `perAppRouting`.

## Validation

`ConnectionSettings.validate()` returns field-level errors; the ViewModel refuses
to persist an invalid state. DNS accepts `https://…`, `tls://host[:port]`,
`udp://ip[:port]`, `quic://…`, `h3://…`, plain IPv4/IPv6, or `local`.

## Logs

`LogBuffer` keeps the last 2000 lines from `CoreAdapter.logs` (already redacted by
the adapter) and `ConnectionManager.events`, in memory only. Nothing is written to
disk; "Share" uses `ACTION_SEND` with plain text and the user chooses the target.
