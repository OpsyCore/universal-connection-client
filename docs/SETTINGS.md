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
| IPv6 | `ipv6` | tun `address` gains `fdfe:dcba:9876::1/126`; `dns.strategy` = `prefer_ipv4` when on, `ipv4_only` when off |
| MTU | `mtu` (1280–9000) | `inbounds[tun].mtu` |
| TLS fragment (v1.0.1, default off) | `tlsFragment` | proxy outbound `tls.fragment: true`, `tls.record_fragment: true`, `tls.fragment_fallback_delay: "500ms"` — only for vless/vmess/trojan/http outbounds that have `tls.enabled`; **not** applied to REALITY, hysteria/hysteria2/tuic (QUIC), shadowsocks, socks, wireguard. sing-box ≥1.12 syntax; Xray `fragment.length/interval` is never emitted. |
| FakeDNS (v1.0.1, default off, needs `capabilities.fakeIp`) | `fakeDns` | `dns.servers += { type: fakeip, tag: dns-fakeip, inet4_range: 198.18.0.0/15, inet6_range: fc00::/18 }` (typed 1.12+ server; legacy `dns.fakeip` block never emitted) + rules: LAN suffixes (`.local .lan .home .home.arpa .internal .localhost .localdomain`) → `dns-direct` (only when bypass-LAN is on), then `query_type: [A, AAAA] → dns-fakeip`. **IPv6 off:** `inet6_range` omitted and only `A` is faked, so no AAAA answer can exist while `strategy = ipv4_only`. Non-A/AAAA queries and the proxy host still use the real resolvers; `independent_cache` stays on. |
| Speed in notification (v1.0.1, default off) | `notificationSpeed` — **UI-only, not in `CoreStartOptions`** | `UcVpnService` combines connection state, this switch and `ConnectionManager.statistics` (1 s ticks from libbox `CommandClient`, null when the core is not running). The rate line `↓ x/s · ↑ y/s` is appended to the notification text only while `Connected`; in any other state, or with the switch off, the notification is posted on state changes only (previous behaviour). `setOnlyAlertOnce`/`setSilent` keep the 1 s re-posts silent; no extra channel. Pure logic in `SpeedMeter` (JVM-tested). |
| Block QUIC (v1.0.1, default off) | `blockQuic` | `route.rules += { protocol: quic, action: reject }` right after `hijack-dns`, before the LAN-bypass and user rules (relies on the `sniff` action already in place). No separate `udp/443` rule, no deprecated `block` outbound. |
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
