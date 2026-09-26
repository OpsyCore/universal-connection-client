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

## Import flow (Phase 2b, `:app`)

`AddConfigScreen` → `AddConfigViewModel` → `ImportRepository` → `core:config`:

```
INPUT (paste | clipboard | QR | document picker | https subscription URL)
  → ConfigImporter.import(text, source)        format detection + parsing (core:config)
  → ImportPlanner.plan(report, existing)        ProfileValidator + CapabilityCheck(CoreCapabilities) + fingerprint dedupe
  → Preview (ProfilePreview: secret-free)       user ticks items; unsupported items savable but not preselected
  → ImportRepository.commit(plan, selected)     atomic JSON store; subscription record saved only here
  → Done                                        never connects automatically
```

- **Duplicate detection** uses `ConnectionProfile.fingerprint` (SHA-256 of canonical content minus id/name/metadata), never the display name; duplicates within one batch are collapsed too.
- **Capabilities** come from the selected `CoreFactory.capabilities` — the UI hard-codes no protocol or transport list. Profiles the current core cannot carry are flagged `Unsupported(reason)` and may still be stored (another core/flavour may carry them).
- **Subscriptions**: fetch over **https only**, 4 MiB cap, `subscription-userinfo` (usage/expiry shown; expired/exhausted flagged), `profile-title`, `profile-update-interval`; the record is persisted with a URL-derived stable id. Refresh and merge rules: see `docs/SUBSCRIPTIONS.md`.
- **QR**: CameraX + ML Kit barcode scanning (bundled on-device model, QR format only). ML Kit is distributed under Google's ML Kit terms (not OSS); recorded in `Notices` as an application dependency. Payload is handed to the importer and never logged.
- **Files**: `ActivityResultContracts.OpenDocument`, any MIME, UTF-8, 4 MiB cap.
- **Clipboard**: `ClipboardManager.primaryClip` read on user tap only (Android 12+ shows the system paste toast).
- **Errors**: every failure is a typed `AddConfigError`/`ConfigError`; snippets shown to the user have `userinfo` replaced by `***` and are capped at 80 chars.

## Not yet implemented

- Xray/v2ray JSON configs (planned: outbound extraction only, same as sing-box).
- Clash YAML (planned: proxies section).
- WireGuard `.conf`.

## Export (Phase 3)

`ShareLinkExporter` (`core:config`) turns a profile back into a standard share link: `vless://`, `vmess://` (v2rayN base64-JSON; URI form only when REALITY/packetEncoding is set), `trojan://`, `ss://` (SIP002, base64 userinfo), `hysteria2://`, `hysteria://`, `tuic://`, `wireguard://`, `socks5://`, `http(s)://`. `export(parse(x))` re-parses to the **same fingerprint** for every protocol (covered by `ShareLinkExporterTest`). Exported text contains credentials; the Servers screen hands it straight to the Android share sheet and never keeps it in UI state or logs.
