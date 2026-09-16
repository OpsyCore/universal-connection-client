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

| Routing rules | `rules: List<RoutingRule>` | one `route.rules` entry per matcher kind (`domain`/`domain_suffix`/`domain_keyword` together, `ip_cidr` separate); `DIRECT`/`PROXY` → `outbound`, `BLOCK` → `action: reject`; emitted after the LAN-bypass rule, before `final: proxy` |
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

## Kill switch (Phase 5, strict)

There is **no kill-switch toggle** in this app. `UcVpnService` reads
`isAlwaysOn` / `isLockdownEnabled` (API 29+) every time it starts and publishes a
`LockdownStatus`; the VPN section shows exactly that state (unknown until the
service has run once) and a button to Android's VPN settings. Below API 29 the
card says the feature is unavailable. `strict_route` is described as in-tunnel
leak protection only.

## Sections

Connection · Routing (LAN bypass, ordered rules, per-app if `perAppRouting`) ·
DNS · VPN (strict route, kill-switch status) · Diagnostics (core log level, logs)
· Appearance (theme; language follows the system) · Data (storage summary,
clear logs) · About (version, core, licences).

## Verification levels

Every networking setting in this phase is **unit-tested (JSON shape) and
CI-built** only. None is device-tested or network-behaviour-tested; see
`docs/CAPABILITIES.md` for the per-feature table and the manual checklist in
`docs/TESTING.md`.
