# Configuration formats

Module: `:core:config` (pure Kotlin, 43 tests). Entry points:

- `ConfigImporter.import(text, source)` — anything: pasted text, QR payload,
  file contents, subscription body. Returns `ImportReport(profiles, failures, format)`.
- `LinkParser.parse(link)` — a single share link → `ParseResult`.

Every failure is a `ConfigError` subclass with a stable `messageKey` for
localisation and a developer `detail`. Failure snippets have credentials
replaced by `***` before they leave the module.

## Detection

| Input | Detected as |
|---|---|
| starts with `{` | sing-box JSON (document with `outbounds`/`endpoints`, or a bare outbound) |
| lines with `scheme://` | share links (one per line; whitespace / `|` separated on one line also accepted; `#`/`//` comment lines skipped) |
| base64 (std or URL-safe, wrapped, padding optional) decoding to the above | subscription body |
| anything else | `UNKNOWN` with one failure |

## Share-link schemes

| Scheme(s) | Notes | Not supported (classified `Unsupported`) |
|---|---|---|
| `vless://` | uuid@host:port; `type` tcp/ws/grpc/http/h2/httpupgrade; `security` none/tls/reality; `flow=xtls-rprx-vision(-udp443)`; `fp`, `alpn`, `sni`, `pbk`, `sid`, `spx`, `allowInsecure`; `?ed=` inside path normalised | `encryption≠none`; other flows. `type=xhttp`/`kcp`/`quic` are **stored** as `Transport.Unsupported` so the profile is kept and flagged in UI |
| `vmess://` | base64 JSON (v2rayN `v:2`) **and** `uuid@host:port?…` URI form; `aid≠0` accepted with warning (AEAD only) | — |
| `trojan://`, `trojan-go://` | TLS on by default; same transport params as VLESS | — |
| `ss://` | SIP002 (base64 or plain `method:password` userinfo), legacy whole-base64, `plugin=obfs-local;…` / `v2ray-plugin`, `uot` | ciphers outside AEAD/2022/legacy list; other plugins |
| `hysteria2://`, `hy2://` | default port 443, `obfs=salamander`+`obfs-password`, `sni`, `insecure`, `mport`/`ports`, `up`/`down` | other obfs; `pinSHA256` ignored with warning |
| `hysteria://`, `hy://` | v1: `auth`, `peer`, `upmbps`/`downmbps` (defaults 10/50 with warning), `obfs`, `alpn` | `protocol≠udp` |
| `tuic://` | `uuid:password@…`, `congestion_control` cubic/new_reno/bbr, `udp_relay_mode` native/quic, `alpn` (default h3), `reduce_rtt` | TUIC v4 |
| `wireguard://`, `wg://` | `privateKey@host:port?publickey&address&presharedkey&reserved&mtu`; keys validated as 32-byte base64 | — (.conf files: Phase 2b) |
| `socks://`, `socks5://`, `socks5h://` | optional `user:pass` (plain or base64) | TLS |
| `http://`, `https://` | HTTP CONNECT; `https` = TLS | — |

IPv6 hosts are accepted with or without brackets. Server names are never
derived from IP literals.

## sing-box JSON import

Only proxy outbound types (`vless vmess trojan shadowsocks hysteria hysteria2
tuic wireguard socks http`) become profiles; `selector`, `urltest`, `direct`,
`block`, `dns` are skipped silently. `route`/`dns`/`inbounds` are ignored —
the app owns those. WireGuard is read from `endpoints` (1.11+) or legacy
outbound form.

## Not yet implemented

- Xray/v2ray JSON configs (planned: outbound extraction only, same as sing-box).
- Clash YAML (planned: proxies section).
- WireGuard `.conf`.
- Subscription fetching + merge (Phase 3).
