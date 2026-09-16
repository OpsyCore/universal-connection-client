# Capability matrix (audited from code, Phase 5)

Source of truth: `SingBoxCapabilities`, `SingBoxConfigGenerator`, `UcVpnService`,
`AndroidPlatformInterface`, `DefaultConnectionManager`, `SingBoxCoreAdapter`.
Core: sing-box/libbox v1.13.21 (build tags `with_gvisor with_quic with_wireguard
with_utls with_clash_api …`).

Verification levels used everywhere in this project:
**U** unit-tested (JVM) · **C** CI-tested · **B** build-verified · **D** device-tested ·
**N** network-behaviour-tested. Device/network testing is NOT AVAILABLE in this
environment; nothing below is marked D or N.

| Capability | Status | Where it lives | Verified | UI |
|---|---|---|---|---|
| IPv4 | SUPPORTED | tun `172.19.0.1/30`, `auto_route`; `Builder.addAddress/addRoute` | U C B | always on |
| IPv6 | SUPPORTED (code) | tun `fdfe:dcba:9876::1/126` + `::/0` when enabled; DNS `strategy` | U C B | toggle |
| DNS configuration | SUPPORTED | typed `dns.servers` (udp/tcp/tls/https/quic/h3/local), remote via `detour: proxy`, direct | U C B | remote/direct server |
| DNS hijacking | SUPPORTED | route rule `protocol: dns → hijack-dns`; TUN `dns_servers` → `Builder.addDnsServer` | U C B | info only (always on) |
| Fake-IP | NOT_SUPPORTED (not implemented) | core can, generator never emits a `fakeip` server | — | hidden |
| Route rules (engine) | SUPPORTED | `route.rules` + `final` | U C B | — |
| Domain rules | SUPPORTED (this phase) | `domain` / `domain_suffix` / `domain_keyword` | U C B | rule editor |
| IP/CIDR rules | SUPPORTED (this phase) | `ip_cidr` | U C B | rule editor |
| Direct / Proxy / Block | SUPPORTED (this phase) | `outbound: direct|proxy`, `action: reject` | U C B | rule editor |
| Split tunnelling (LAN bypass) | SUPPORTED | `ip_is_private → direct` | U C B | toggle |
| Per-app allowlist | SUPPORTED (code) | `include_package` → `TunOptions` → `Builder.addAllowedApplication` | U C B | gated by `capabilities.perAppRouting` |
| Per-app denylist | SUPPORTED (code) | `exclude_package` → `Builder.addDisallowedApplication` | U C B | gated |
| Kill switch (app-level) | REQUIRES_ANDROID_SUPPORT | only Android *Always-on VPN + Block connections without VPN* enforces traffic blocking when the VPN is down; `strict_route` only acts while the tunnel is up | — | **no toggle**; status read via `VpnService.isAlwaysOn()` (API 29+) + button to system VPN settings |
| Network rebinding | SUPPORTED (code) | `AndroidNetworkMonitor` → manager reconnect + `resetNetwork()` + `updateDefaultInterface` | U C B | logged as NETWORK events |
| UDP | SUPPORTED | tun `stack: mixed`; Hysteria/TUIC/WG outbounds | B | — |
| QUIC | SUPPORTED (core) | `with_quic` build tag | B | — |
| Traffic statistics | SUPPORTED | libbox `CommandStatus` → `CoreStatistics` | B | Home |
| Connection logs (per-connection table) | NOT_SUPPORTED | `CommandConnections` not wired | — | hidden |
| Core logs | SUPPORTED | libbox `CommandLog` → `CoreAdapter.logs` (redacted) | U C B | Logs screen |
| Debug diagnostics | PARTIALLY_SUPPORTED | core log level, TCP reachability, share logs; no pcap/HTTP tracing | U C B | Diagnostics |

Note on `CoreCapabilities.fakeIp = true`: it describes what the core *can* do,
not what this app generates. The UI never keys anything off that flag.
