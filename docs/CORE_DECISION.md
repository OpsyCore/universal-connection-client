# Core Decision — which proxy engine(s) the app embeds

Status: **DECIDED (Phase 0)** — single core: **sing-box** via `libbox` (gomobile AAR).
Xray-core is **not** embedded in v1. The Core Adapter abstraction *is* kept so a
second core can be added without touching UI/domain code.

Decision date: 2026-09-16. Re-evaluate at each sing-box minor release.

---

## 1. Candidates evaluated

| | sing-box (`experimental/libbox`) | Xray-core (`libXray` / AndroidLibXrayLite) |
|---|---|---|
| Latest stable at decision time | v1.14.1 (2026-09-15); v1.13.21 (2026-08-30) | v25.x line |
| License | **GPLv3** + "no derivative may use the name or imply association" | **MPL-2.0** |
| Android binding | First-party `libbox` (gomobile), `PlatformInterface` designed for `VpnService` | Community bindings (`libXray`, `AndroidLibXrayLite`), maintained outside core |
| TUN stack | Built in (`sing-tun`: system / gVisor / mixed), auto-route, per-app include/exclude via `TunOptions` | Needs a separate tun2socks (hev-socks5-tunnel / tun2socks) → two processes/stacks to manage |
| Protocols | VLESS, VMess, Trojan, Shadowsocks (incl. 2022), Hysteria, **Hysteria2**, **TUIC**, **WireGuard**, SOCKS, HTTP, ShadowTLS, AnyTLS, NaiveProxy(optional), SSH, Tor(opt) | VLESS (+XTLS Vision, **REALITY origin**), VMess, Trojan, Shadowsocks, WireGuard, SOCKS, HTTP, **XHTTP/SplitHTTP**; **no** Hysteria2, **no** TUIC natively |
| REALITY / uTLS | Yes (client side, `with_utls`) | Yes (reference implementation) |
| UDP | Full (TUN handles UDP natively) | Depends on the tun2socks layer |
| DNS | Full DNS server/route engine, FakeIP, DNS hijack via TUN | Built-in DNS, hijack requires tun2socks cooperation |
| Routing | Rule sets (binary, remote-updatable), geosite/geoip, process/package rules | Routing rules + geosite/geoip dat files |
| Runtime stats | Clash API / command server (traffic, connections, URL test, groups) | Stats API (gRPC) |
| APK size impact (arm64) | ~25–35 MB uncompressed .so | ~20–30 MB + tun2socks |
| Build in CI | `make lib_install && make lib_android` — Go 1.26 + NDK r28 + JDK17 (verified in upstream CI) | libXray build script (Go + gomobile) + separate tun2socks build |
| Maintenance risk | High release cadence, breaking config-schema changes between minors (mitigated by pinning a tag + generating config from our own model) | Stable JSON schema; bindings lag core |

## 2. Requirement coverage (mandate §6)

| Required protocol | sing-box | Xray |
|---|---|---|
| VLESS | ✅ | ✅ |
| VMess | ✅ | ✅ |
| Trojan | ✅ | ✅ |
| Shadowsocks | ✅ | ✅ |
| Hysteria / Hysteria2 | ✅ | ❌ |
| TUIC | ✅ | ❌ |
| WireGuard | ✅ | ✅ (outbound) |
| SOCKS / HTTP | ✅ | ✅ |

sing-box alone covers 100 % of the mandated list. Xray alone does not (no
Hysteria2 / TUIC). Embedding both would double native size, double the
config-generation surface, and require a tun2socks layer only for Xray.

## 3. What we lose by not embedding Xray (documented, not hidden)

- **XHTTP (SplitHTTP)** transport — Xray-only. Links using `type=xhttp` will be
  parsed and stored, but flagged **"unsupported by current core"** at
  validation time and not shown as connectable. (sing-box 1.13 stable does not
  include XHTTP; several forks add it but we do not build forks.)
- **XTLS-Vision** flow is supported by sing-box's VLESS outbound (`flow=xtls-rprx-vision`) — no loss.
- Some exotic Xray transports (`kcp`, `xtls` legacy) — not supported; same handling as XHTTP.

If product requirements later demand XHTTP, the Core Adapter (`core/engine-api`)
lets an `XrayCoreAdapter` be added; routing/TUN would then need a tun2socks
strategy which is out of scope for v1.

## 4. Licensing consequence (needs owner acknowledgement)

Embedding `libbox` makes the APK a **derivative work of a GPLv3 program**.
Therefore:

1. The application source must be distributed under **GPL-3.0-or-later**
   (compatible with Hiddify, NekoBox and SFA, all of which do the same).
   A `LICENSE` file (GPLv3) is added to the repository root in Phase 1.
2. The app is branded independently. It must not be called "sing-box …",
   must not use the sing-box logo, and must not imply endorsement — this is
   the extra clause in sing-box's LICENSE.
3. Third-party notices (sing-box, sing-tun, gVisor, quic-go, wireguard-go,
   etc.) are shown in-app under Settings → About → Licenses (Phase 7).

**If the owner needs a proprietary/closed license for this app, stop: this
decision must be reversed (Xray/MPL) with the protocol losses above.** Per the
mandate's stop conditions, this is called out explicitly in the Phase 0 report.

## 5. Pinned version and build

- Pinned tag: **`v1.13.21`** (last 1.13 stable). Rationale: 1.14.0 shipped
  ~2 weeks ago with a larger `PlatformInterface` (shell, bridge, SSH, Tailscale
  hooks) that we do not need yet; 1.13's interface is smaller and its config
  schema is what our generator targets first. The upgrade to 1.14 is a planned
  task with a checklist in `docs/VPN_ENGINE.md`.
- Build recipe (CI job `libbox`): Go 1.26.x, NDK r28, Temurin JDK 17,
  `make lib_install && make lib_android` at the pinned tag, producing
  `libbox.aar` (minSdk 24) — the app's minSdk is 24 so the legacy AAR is not used.
- Build tags = upstream defaults for Android (`with_quic, with_wireguard,
  with_utls, with_clash_api, …`). The AAR SHA-256 is recorded in
  `core/engine-singbox/libbox.sha256` and verified by the Gradle build before
  use, so a tampered artifact cannot be linked silently.
- The AAR is **not committed** to Git (≈90 MB, excluded by `.gitignore`); CI
  builds it once per tag and caches it; local developers download it from the
  CI artifact (`docs/RELEASE.md`).

## 6. Integration shape

```
core/engine-api        CoreAdapter (start/stop/reload, events, stats, urltest)
core/engine-singbox    SingBoxConfigGenerator (ConnectionProfile → sing-box JSON)
                       SingBoxCoreAdapter    (libbox CommandServer/Client wrapper)
                       PlatformBridge        (libbox.PlatformInterface ← VpnService)
core/vpn               UcVpnService (VpnService), ConnectionManager, NetworkMonitor
```

UI and domain never import `io.nekohasekai.libbox.*`.

## 7. Revisit triggers

- sing-box 1.14 becomes the oldest supported line → upgrade pin.
- Owner requires XHTTP or a closed license → add Xray adapter / change core.
- A first-party Maven artifact for libbox appears → drop the CI build job.
