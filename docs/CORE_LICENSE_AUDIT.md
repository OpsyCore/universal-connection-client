# Core & Licensing Decision Audit — sing-box/libbox vs Xray-core

Date: 2026-09-16 · Branch: `arena/01a0aa91-universal-connection-client` @ `337d414`
Scope: engineering audit of the **actual repository** and the **actual upstream
sources** (fetched via GitHub API on the date above). No production code was
changed for this audit.

> **Legal disclaimer.** This document is an engineering analysis, not legal
> advice. Throughout, statements are tagged:
> **[CONFIRMED]** = verbatim from a license text or a build file;
> **[ENGINEERING INTERPRETATION]** = our reading of how the license maps to
> this architecture; **[LEGAL COUNSEL]** = requires a lawyer's opinion.

---

## 0. Executive summary

| | A) Keep sing-box/libbox | B) Migrate to Xray-core |
|---|---|---|
| Core license | GPLv3 **+ non-standard naming/association clause** [CONFIRMED] | MPL-2.0 [CONFIRMED] — **but Xray-core links GPLv3 `sagernet/sing` for Shadowsocks-2022** [CONFIRMED, see §3.7] |
| Fits current code | 100 % — built, tested, CI green | ~55 % of core-facing code must be rewritten; 4 of 10 protocols/product features need extra work (§4) |
| Proprietary app code possible? | **Very likely no** while libbox is in the same APK/process [ENGINEERING INTERPRETATION; LEGAL COUNSEL] | **Possible in principle** (MPL is file-level) — *if* Xray is built without the GPL `sing` dependency or counsel accepts that linkage [LEGAL COUNSEL] |
| TUN on Android | Native, mature (sing-tun; auto-route, strict-route, per-app, DNS hijack) | Experimental TUN inbound since v26.1.23 (fd-based, gVisor only, "no ICMP", routing/DNS/per-app done by *us*) [CONFIRMED] |
| Hysteria2 / TUIC | Both native | Hysteria2 added Jan-2026 (new); **TUIC absent** [CONFIRMED] |

**Technical recommendation:** keep sing-box (§7.1).
**Licensing/product recommendation:** if the business goal is a *closed-source
commercial Android app*, sing-box is very probably incompatible with that goal;
Xray-core is the more appropriate direction, **conditional** on a counsel
review of the `sagernet/sing` GPL dependency inside Xray (§7.2). A
non-migration middle path (dual build flavours / open-source app) is described in §7.3.

---

## 1. Current sing-box architecture (as it exists in the repo)

### 1.1 Module dependency graph (from `settings.gradle.kts` + each `build.gradle.kts`)

```
:app ──────────────┬─► :core:vpn ────────────► :core:engine-singbox ──► libs/libbox.aar   (GPLv3)
                   ├─► :core:engine-singbox ─┘        │                     │
                   ├─► :core:singbox-config ◄─────────┘                     │
                   ├─► :core:engine-api ◄── (api) ─── all of the above      │
                   ├─► :core:model                                          │
                   └─► :core:config (Phase 2a, JVM)                         │
                                                                             │
Pure JVM, no libbox symbol:  model · engine-api · singbox-config · config    │
Android, imports libbox:     engine-singbox (5 files)  ◄─────────────────────┘
Android, no libbox import but compile-depends on it:  vpn (implementation), app (implementation)
```

Direct import of `io.nekohasekai.libbox.*` occurs in exactly **5 files**, all in
`core/engine-singbox`:

| File | libbox symbols used |
|---|---|
| `LibboxRuntime.kt` | `Libbox.setup(SetupOptions)`, `Libbox.redirectStderr`, `Libbox.version` |
| `AndroidPlatformInterface.kt` | `PlatformInterface`, `TunOptions`, `InterfaceUpdateListener`, `ConnectionOwner`, `Libbox.InterfaceType*` |
| `LocalDnsTransport.kt` | `LocalDNSTransport`, `ExchangeContext` |
| `Iterators.kt` | `StringIterator`, `RoutePrefixIterator` adapters |
| `SingBoxCoreAdapter.kt` | `CommandServer`, `CommandClient`, `OverrideOptions`, `StatusMessage`, `LogIterator`, `Libbox.checkConfig`, `Libbox.CommandStatus/CommandLog` |

`app/di/AppGraph.kt` references `SingBoxCoreAdapter` (one constructor call) — the
only place outside the engine module that names the concrete core.
`core/vpn` has **zero** sing-box symbols; it depends on `engine-singbox` only
because `UcVpnService`/`AndroidTunnelHost` were placed behind the adapter for
the notification/registry wiring (that dependency can be inverted; see §4).

### 1.2 How libbox is linked

- `core/engine-singbox/build.gradle.kts`: `implementation(files("libs/libbox.aar"))` [CONFIRMED].
  The AAR is **not committed**; CI builds it from `SagerNet/sing-box@v1.13.21`
  (`.github/workflows/libbox.yml` → `tools/build-libbox.sh` → `make lib_install && go run ./cmd/internal/build_libbox -target android`) and caches it by tag.
- Integrity pin: `core/engine-singbox/libbox.sha256` = `ff2890c1…8da9a` (verified by `VerifyLibboxTask` before every compile) [CONFIRMED].
- The AAR contains `classes.jar` (gomobile Java bindings, package `io.nekohasekai.libbox` + `go.*`) and `jni/<abi>/libbox.so` (statically linked Go runtime + sing-box + all Go deps). The `.so` is loaded **in the app's main process** (`android:process` was intentionally removed from `core/vpn/AndroidManifest.xml`).
- `consumer-rules.pro` keeps `io.nekohasekai.libbox.**` and `go.**` from R8.
- ABIs packaged (from `app/build.gradle.kts`): `arm64-v8a`, `armeabi-v7a`, `x86_64`. AAR produced by CI = 92.5 MB (4 ABIs, uncompressed); debug APK artifact = 86.6 MB.
- libbox build tags (from `cmd/internal/build_libbox/main.go@v1.13.21`) [CONFIRMED]: `with_gvisor with_quic with_wireguard with_utls with_naive_outbound with_clash_api with_tailscale (+ts_omit_*) with_low_memory`.

### 1.3 CoreAdapter ↔ libbox (`SingBoxCoreAdapter`)

`CoreAdapter` (engine-api, core-agnostic) → `SingBoxCoreAdapter` (282 lines):

| `CoreAdapter` member | libbox call |
|---|---|
| `start(profile, options, tun)` | `SingBoxConfigGenerator.generate()` → JSON → `Libbox.checkConfig` → `CommandServer(handler, platform).start()` → `startOrReloadService(json, OverrideOptions)` → `TunnelProbe` (real HTTP 204 through the tunnel) |
| `reload` | `startOrReloadService` (CommandServer kept, inner box restarted) |
| `stop` | `closeService()` + `close()` |
| `onNetworkChanged` | `resetNetwork()` + `InterfaceUpdateListener.updateDefaultInterface(name,index,expensive,constrained)` |
| `onPause/onResume` | `pause()/wake()` |
| `statistics` | `CommandClient(CommandStatus, 1 s)` → `StatusMessage{uplink, downlink, uplinkTotal, downlinkTotal, connectionsIn/Out, memory, goroutines}` |
| `logs` | `CommandClient(CommandLog)` → `LogIterator` → redaction (`redact()`) |
| `urlTest` | not yet wired to libbox `urlTest` (uses `TunnelProbe`) |

### 1.4 TUN / VPN

- `AndroidPlatformInterface.openTun(TunOptions)` translates sing-box's computed
  `TunOptions` (addresses, routes, exclude-routes/route-ranges by API level,
  DNS servers, MTU, include/exclude packages, HTTP proxy) into `TunRequest` →
  `UcVpnService.openTun()` → `VpnService.Builder` → returns fd to Go.
- `autoDetectInterfaceControl(fd)` → `VpnService.protect(fd)`.
- sing-tun does routing computation, `auto_route`, `strict_route`, stack `mixed`
  (system TCP + gVisor UDP), per-app include/exclude via `OverrideOptions`.
- Underlying-network changes: `AndroidNetworkMonitor` (`INTERNET & NOT_VPN`) → `updateDefaultInterface`.

### 1.5 DNS

Generated in `SingBoxConfigGenerator.dns()`: typed servers (`udp/tcp/tls/https/quic/h3/local`),
`hijack-dns` route action on port 53 inside TUN, proxy hostname resolved via the
`local` server, which is served by `LocalDnsTransport` → `android.net.DnsResolver`
bound to the **underlying** `Network` (raw queries on API 29+).

### 1.6 Routing

Generated in `SingBoxConfigGenerator.route()`: `auto_detect_interface`, `sniff`,
`hijack-dns`, private/local bypass; `CoreStartOptions.routingConfig` accepts a
JSON fragment for Phase 6 rules (domain/IP/CIDR/rule-set/per-app via
`include_package`/`exclude_package`). Not yet exposed in UI.

### 1.7 Logging / status

libbox `CommandServer` + `CommandClient` over a Unix socket in `basePath`; log
lines redacted before entering `Flow<CoreLogLine>`; statistics displayed only
when the client is connected (no fake zeros).

### 1.8 Configuration generation

`core/singbox-config` (392 lines, 16 tests): `ConnectionProfile` → sing-box
1.13 JSON. Covers all 10 protocols, TCP/WS/gRPC/HTTP/HTTPUpgrade transports,
TLS/uTLS/Reality, WireGuard `endpoints`, TUN inbound, DNS, route. Deterministic.

### 1.9 Licensing-relevant dependency tree

**Kotlin/Android side (Gradle catalog):** all Apache-2.0 (AndroidX, Compose,
kotlinx, Material), EPL-1.0/2.0 test-only (JUnit 4), MIT (Turbine, Robolectric)
— no copyleft. [CONFIRMED via `gradle/libs.versions.toml`]

**Go side compiled into `libbox.so` (from `sing-box/go.mod@v1.13.21`)** — the
ones that matter:

| Module | License (GitHub API / LICENSE file) | Note |
|---|---|---|
| `SagerNet/sing-box` | GPL-3.0-or-later **+ extra clause** | see §2.1 |
| `SagerNet/sing`, `sing-tun`, `sing-quic`, `sing-vmess`, `sing-shadowsocks2`, `sing-mux`, `sing-shadowtls`, `cronet-go` | GPL-3.0-or-later (standard text, **no** extra clause) [CONFIRMED by reading each LICENSE] | GitHub reports `NOASSERTION` because of the copyright header layout |
| `SagerNet/quic-go`, `SagerNet/wireguard-go` | MIT | |
| `SagerNet/gvisor` | Apache-2.0 | |
| `SagerNet/gomobile` | BSD-3-Clause | |
| `metacubex/utls` | BSD-3-Clause | |
| `SagerNet/tailscale` (`with_tailscale`) | BSD-3-Clause | compiled in by default tags |
| `anthropic-sdk-go`, `openai-go` | MIT/Apache | present in go.mod (LLM-based rule features), pulled into the binary only if referenced by built packages — **not verified**; irrelevant to licensing class |

**Conclusion of §1.9 [CONFIRMED]:** the native library is a GPLv3 work; nothing
in the remainder of the app is copyleft.

---

## 2. GPLv3 compliance analysis

### 2.1 The license text [CONFIRMED]

`sing-box/LICENSE@v1.13.21` = GPL-3.0-or-later notice **plus**:

> "In addition, no derivative work may use the name or imply association with this application without prior consent."

The sub-libraries (`sing`, `sing-tun`, …) carry plain GPL-3.0-or-later without this sentence.

### 2.2 Which components are affected

| Component | Contains GPL code? | Affected by copyleft? |
|---|---|---|
| `libbox.aar` (`libbox.so`, `classes.jar`) | Yes — it *is* the GPL work | Yes (verbatim redistribution: GPL §4/§6 apply) |
| `:core:engine-singbox` (our 726 lines of Kotlin calling libbox) | No | **Very likely yes** — it is written against libbox's API and cannot function without it [ENGINEERING INTERPRETATION] |
| `:core:vpn`, `:app` (same process, same APK, statically compiled dependency) | No | **Likely yes** under FSF's reading (single program = the APK) [ENGINEERING INTERPRETATION; LEGAL COUNSEL] |
| `:core:model`, `:core:engine-api`, `:core:config`, `:core:singbox-config` | No | Independently, no. But they ship in the same APK; whether they are "separate and independent works" (GPL §5 last paragraph, "aggregate") or parts of one combined work is the crux [LEGAL COUNSEL] |

### 2.3 Derivative-work concern for the current linking model

Facts [CONFIRMED]:
1. `libbox.so` is loaded by `System.loadLibrary` into the **application process** (no separate `:vpn` process, no IPC boundary).
2. Our code implements libbox-defined Java interfaces (`PlatformInterface`, `CommandServerHandler`, `LocalDNSTransport`, …) — a tight, API-level coupling, not a generic protocol.
3. Everything is shipped in **one APK/AAB**.

Interpretation [ENGINEERING INTERPRETATION]: This is the pattern the FSF GPL FAQ
treats as one combined program (dynamic linking + shared address space +
intimate data structures). We should assume the **whole APK must be offered
under GPLv3** unless counsel advises otherwise. A process-separation design
(`android:process=":vpn"` + Binder/AIDL) is sometimes argued to change this; the
FSF FAQ says communication via "complex internal data structures" still forms
one program, and our `PlatformInterface` callbacks are exactly that. Moving to
a separate process would **not** reliably remove the concern. [LEGAL COUNSEL]

### 2.4 Distribution obligations if we ship the APK/AAB (assuming §2.3 applies)

[CONFIRMED — GPLv3 text]:
- §4/§5: license notices, keep copyright notices, state modifications (we do not modify sing-box; we only build it).
- §6: provide **Corresponding Source** for the GPL parts — which, under the combined-work reading, means *the whole app's source* — via one of §6(a)–(e): bundle source, written offer valid 3 years, or (for network distribution such as an app store) "equivalent access to copy the Corresponding Source from the same place at no charge" (§6(d)).
- §6 "Installation Information" (anti-tivoization) applies to "User Products" — an Android phone is a consumer product; for an app store distribution where the *user* installs, this is generally considered satisfied, but note it [LEGAL COUNSEL].
- §7 additional terms: the sing-box naming/association clause is an additional *restriction*. GPLv3 §7 allows only enumerated additional terms; "(c) Prohibiting misrepresentation of the origin" and "(d) Limiting the use for publicity purposes of names of licensors or authors" plausibly cover it. We must comply with it regardless [CONFIRMED that we must; how broadly "imply association" reaches is LEGAL COUNSEL].
- §10/§11: no further restrictions may be imposed; patent grant; **Google Play's terms vs GPL**: historically debated (the "Play Store vs GPL" question) — commonly considered acceptable for GPLv3 apps today; verify [LEGAL COUNSEL].

Practical checklist we would need to ship:
1. `LICENSE` = GPLv3 (already added).
2. In-app "Open-source licenses" screen listing sing-box (GPLv3 + clause), sing-* (GPLv3), gVisor (Apache), quic-go/wireguard-go (MIT), utls/gomobile/tailscale (BSD), plus the AndroidX notices.
3. Public source repository (or written offer) matching each released build, including the exact `singbox.version` + `libbox.sha256` and the reproducible `tools/build-libbox.sh`.
4. No use of "sing-box"/"SFA"/"SagerNet" in app name, icon, store listing or marketing; the only mention should be the factual license notice. Current branding ("Universal Connection Client", `io.ucc.app`) complies.

### 2.5 Can UI / Config Engine / Smart Engine / Server Manager stay proprietary?

- Under the combined-work reading (§2.3): **no** — not while they are part of the same distributed program as libbox. [ENGINEERING INTERPRETATION]
- The only architecture that commonly *is* accepted as an "aggregate" is a
  **genuinely separate program** communicating over an arm's-length interface
  (e.g. a separately installed GPL "core" app exposing a documented
  socket/AIDL API, with our proprietary app talking to it at arm's length —
  the way some clients talk to an external `sing-box` binary). This would
  require: two APKs, user installs both, our app never links `classes.jar`,
  no `PlatformInterface` implementation on our side (the core app would need
  its own VpnService). It defeats the product's UX and is still not
  risk-free. [LEGAL COUNSEL]
- **Pure-Kotlin modules are reusable regardless:** `core/model`, `core/engine-api`, `core/config`, and any future `core/smart`/`core/data` contain no GPL code; if the app later switches to an MPL core, these modules can be relicensed by us at will (we hold the copyright).

### 2.6 Branding / association restrictions

[CONFIRMED] "no derivative work may use the name or imply association with this application without prior consent" (sing-box only; not on sing-*). Impact: cannot call the product "sing-box client", cannot use its logo, should not describe it as "powered by sing-box" in marketing without consent. A neutral factual license notice is required by GPL itself and is [ENGINEERING INTERPRETATION] not "implying association" — but this is exactly the kind of sentence counsel should read.

---

## 3. Xray-core / MPL-2.0 analysis (actual state, checked 2026-09-16)

Repository `XTLS/Xray-core`: license **MPL-2.0** [CONFIRMED]; latest *stable* release **v26.3.27** (2026-03-27); newer tags `v26.6.27, v26.7.11, v26.7.28, v26.9.8, v26.9.9` are all marked **pre-release** [CONFIRMED]. Pushed today (active).

### 3.1 Android integration options

| Option | What it is | License | State |
|---|---|---|---|
| `XTLS/libXray` (official wrapper) | gomobile AAR around Xray; JSON-RPC style `invoke(apiVersion:3)`; `runXray`, `pingBatch`, `testXray`, `controller.setDNS`, socket-protect controller | MIT (wrapper) | active (pushed 2026-09-13), pins Xray **v26.9.9 pre-release**; "only compatible with the latest release of Xray-core" [CONFIRMED from README] |
| `2dust/AndroidLibXrayLite` (v2rayNG) | older gomobile wrapper | **LGPL-3.0** | active; LGPL would reintroduce copyleft questions for static Go linking |
| Own gomobile binding | `gomobile bind` of a thin Go package we write | our choice | most control; we already have the CI pattern (Go + NDK + gomobile) |

Realistic choice: **own thin gomobile binding** (or a fork of libXray), because we need fd-based TUN handoff, socket protect, stats and a sane API surface — libXray's string-JSON RPC is workable but coarse.

### 3.2 TUN

[CONFIRMED from `proxy/tun/README.md`, `tun_android.go`, release v26.1.23]:
- TUN inbound exists since **v26.1.23 (Jan 2026)**; Android reads the fd from env `xray.tun.fd`; gVisor `fdbased` endpoint; IPv4/IPv6, TCP/UDP.
- Limitations stated by upstream: **no ICMP**; "connection success is only a mark of accepting the packet" (SYN-ACK to any host); **"does not contain options to configure network level addresses, routing or rules … OS is what should manage it"**; only config = `name`, `MTU`.
- Consequence: everything sing-tun currently does for us — address/route computation, `auto_route`/`strict_route`, exclude routes, per-app package lists, DNS hijack inside the tunnel, underlying-interface rebinding on network change — would move to **our** `UcVpnService` + Xray routing/DNS config. Feasible (Android's `VpnService.Builder` is where routes are set anyway) but new code, new tests, and new edge cases that sing-tun has already solved over years.
- Alternative: keep a proven tun2socks (`heiher/hev-socks5-tunnel`, MIT, C/NDK; or `xjasonlyu/tun2socks`, MIT, Go) in front of Xray's SOCKS inbound — the classic v2rayNG/Hiddify-old design. Adds a second native library, double packet copy, and separate UDP behaviour, but is mature.

### 3.3 Protocol coverage (`proxy/` @ v26.3.27) [CONFIRMED]

`blackhole dns dokodemo freedom http hysteria loopback shadowsocks shadowsocks_2022 socks trojan tun vless vmess wireguard`

| Required | Xray | Note |
|---|---|---|
| VLESS (incl. Vision, Reality, new PQ encryption) | ✅ reference implementation | superset of sing-box |
| VMess | ✅ | |
| Trojan | ✅ | |
| Shadowsocks (AEAD + 2022) | ✅ | **2022 variant imports GPL `sagernet/sing`** (§3.7) |
| Hysteria **2** | ✅ new (v26.1.23): "version 2, udphop, Salamander" | young; masquerade-style config; no v1 |
| Hysteria 1 | ❌ | drop or keep flagged unsupported |
| TUIC | ❌ **absent** | must drop the protocol or build it ourselves (forbidden by our rules: no custom crypto protocol implementations) |
| WireGuard | ✅ (`golang.zx2c4.com/wireguard`, gVisor netstack) | |
| SOCKS / HTTP | ✅ | |

### 3.4 Transports / TLS [CONFIRMED from `transport/internet/`]

`tcp websocket grpc httpupgrade splithttp(XHTTP) kcp(mKCP) hysteria reality tls headers finalmask browser_dialer`.
Reality ✅ (origin), uTLS via `refraction-networking/utls` ✅, ECH ✅ (v26.3), XHTTP/mKCP/finalmask ✅ (Xray-only — today we store these as `Transport.Unsupported`; with Xray they become supported).
QUIC transport (legacy `quic` type) removed upstream; Hysteria2 covers the QUIC use case.

### 3.5 DNS / routing / UDP / IPv4-v6

- DNS app (`app/dns`): UDP/TCP/DoH/DoQ/fakedns, per-domain server selection, `queryStrategy` for v4/v6 ✅. No "bind to Android underlying network" hook of libbox's `LocalDNSTransport` — we would use `controller.setDNS` + socket protect (libXray pattern) or our own binding.
- Routing (`app/router`): domain/ip/geosite/geoip/port/network/protocol/user/inboundTag rules ✅; **no per-app (package) rules** — per-app split tunnelling would be done purely at the `VpnService.Builder` level (allowed/disallowed apps), which is fine for "app in/out of tunnel" but cannot do "app X → outbound Y".
- UDP: full-cone via `udp_fullcone.go` in TUN ✅; UDP over TCP for SS ✅.
- IPv6 ✅.

### 3.6 Logging / status / control

- `app/stats` + `app/commander` (gRPC API) or `app/metrics` (expvar HTTP) for traffic counters; libXray exposes `queryStats` via JSON. No push-style status stream — we would poll (1 s) like we already do.
- Logs: Xray writes to stdout/file; libXray offers callback-less logging → we would read a log file or set a custom `log.Handler` in our own Go binding.
- Health check: `app/observatory` (URL test per outbound) — useful for Smart Engine.
- Hot reload: **none** — restart core on profile switch (we already handle this in `DefaultConnectionManager`, marginal UX cost).

### 3.7 Licensing obligations under MPL-2.0 + third-party licenses

**MPL-2.0 [CONFIRMED]:** file-level copyleft (§1.10 "Covered Software", §3.1–3.3). Obligations: keep MPL notices; if we *modify* Xray source files, publish those files' source; **Larger Work (§3.3)** — we may combine Xray with proprietary code and distribute the combination under our own terms, provided the MPL files remain MPL and their source is available. **No obligation to publish our Kotlin/Go binding code** (unless our Go binding package is itself derived from MPL files — writing our own binding file that merely *imports* Xray packages is not modification of Covered Software [ENGINEERING INTERPRETATION, widely accepted for MPL]).

**Third-party Go deps of Xray (`go.mod@v26.3.27`) [CONFIRMED]:**

| Dep | License | Concern |
|---|---|---|
| `xtls/reality` | MPL-2.0 | none |
| `refraction-networking/utls`, `cloudflare/circl` | BSD-3 | none |
| `apernet/quic-go` (Hysteria2), `quic-go/qpack` | MIT | none |
| `gvisor.dev/gvisor` | Apache-2.0 | none |
| `golang.zx2c4.com/wireguard`, `wintun` | MIT | none |
| `google.golang.org/grpc`, `protobuf`, `genproto` | Apache/BSD | none |
| `miekg/dns`, `gorilla/websocket`, `klauspost/*`, `pires/go-proxyproto`, `juju/ratelimit`, `lukechampine.com/blake3`, `ghodss/yaml`, `pelletier/go-toml`, `vishvananda/*` | BSD/MIT/Apache/LGPL-3 (`juju/ratelimit` is **LGPL-3.0**) | `juju/ratelimit` is LGPL; Go static linking + LGPL is a known grey area, generally handled by providing relinkable objects/source of the LGPL part [LEGAL COUNSEL, low risk] |
| **`github.com/sagernet/sing` v0.5.x, `sagernet/sing-shadowsocks`** | **GPL-3.0-or-later** [CONFIRMED: LICENSE files read] | **Imported by Xray in `common/singbridge/*`, `proxy/shadowsocks_2022/*`, `infra/conf/shadowsocks.go`** [CONFIRMED via code search, 14 files]. Statically linked into any Xray binary that includes Shadowsocks-2022 (the default build does). |

**This last row is the most important licensing finding of the audit.** An
Xray-core binary built with default tags contains GPLv3 code from
`sagernet/sing`. Upstream distributes it under MPL nonetheless; whether that is
compatible (MPL-2.0 §3.3 permits combining with GPL as a Larger Work *only if
the combination is then distributed under GPL for the GPL parts*) is a known,
publicly discussed inconsistency in the Xray project and is **unresolved
upstream**. For *our* goal (proprietary app), the engineering mitigation is to
**build Xray without the `shadowsocks_2022` package and `singbridge`** (custom
`main`/registration list — Xray's `main/distro/all` is just an import list, so
a custom distro file omitting `shadowsocks_2022` is straightforward), verify with
`go version -m` / `go list -deps` that no `sagernet/*` module remains in the
binary, and document the SBOM. Cost: Shadowsocks-2022 ciphers become
unsupported (classic AEAD SS still works via Xray's own `proxy/shadowsocks`).
Whether this fully resolves the question is [LEGAL COUNSEL].

### 3.8 Native build / ABI

Same toolchain as today: Go + NDK r28 + gomobile → AAR. Our `libbox.yml`/`tools/build-libbox.sh` pattern transfers 1:1 (replace repo/tag/make target). Expected `.so` size: smaller than libbox (no tailscale, no naive/cronet, no clash API) — typically 25–35 MB/ABI vs ~23 MB/ABI for libbox 1.13 with `with_low_memory`… in practice comparable; the 92 MB AAR today is dominated by four ABIs. Not a decisive factor.

---

## 4. Migration impact (estimate — nothing migrated)

### 4.1 What stays (core-agnostic, no changes) — ≈ 1 950 lines + 60 tests

| Module | Lines | Tests | Change |
|---|---|---|---|
| `core/model` | ~400 | 0 | none (maybe add `Transport.Xhttp/Kcp` since they'd become supported) |
| `core/engine-api` (`CoreAdapter`, `TunProvider`, `ConnectionState/Error`, `DefaultConnectionManager`, `Ports`) | 685 | 17 | none — this is the seam the design was built around |
| `core/config` (parsers) | ~900 | 43 | none |
| `core/vpn` (`UcVpnService`, `AndroidTunnelHost`, `AndroidNetworkMonitor`, registry) | 465 | 0 | small: route/DNS computation that sing-tun did must be added (or provided by generator) — est. +150 lines; drop `implementation(project(":core:engine-singbox"))` |
| `app` (UI, ViewModel, stores) | ~600 | 0 | one line in `AppGraph.kt` |

### 4.2 What is replaced

| Today | Replacement | Est. size | Notes |
|---|---|---|---|
| `core/singbox-config` (392 lines, 16 tests) | `core/xray-config`: `ConnectionProfile` → Xray JSON (`outbounds`, `streamSettings`, `tlsSettings/realitySettings`, `dns`, `routing`, `inbounds:[tun]`, `stats/policy`) | ~450 lines, ~20 tests | full rewrite; same shape/tests approach |
| `core/engine-singbox` (726 lines) | `core/engine-xray`: Kotlin adapter + **our own Go binding** (`gomobile`): start/stop with fd, protect callback, stats query, log handler, URL test | ~500 Kotlin + ~300 Go | `LocalDnsTransport`, `PlatformInterface`, `Iterators` have no counterpart; `TunnelProbe` reused |
| `tools/build-libbox.sh`, `.github/workflows/libbox.yml`, `libbox.sha256`, `singbox.version` | `build-libxray.sh`, `libxray.yml`, pins | small | same pattern |
| `docs/CORE_DECISION.md`, `VPN_ENGINE.md`, `CONFIG_FORMATS.md` | rewrite core sections | — | |
| `consumer-rules.pro` | keep `go.**` + new package | trivial | |

### 4.3 What must be *built new* (features sing-box gave us for free)

1. **Route table logic** for the TUN: default routes + exclusions (LAN, server IP), per-API-level `excludeRoute` vs range splitting — was `TunOptions` from sing-tun. (~150 lines + tests; we already have `IpPrefix` and the Builder code.)
2. **Strict-route / leak prevention** semantics: with sing-box, `strict_route` closes gaps (e.g. bypass via other interfaces); with Xray we rely purely on Android's VPN routing + `VpnService.Builder` + `setBlocking`, and "kill switch" becomes always-on/lockdown deep-link only (same as today's honest limitation).
3. **DNS inside the tunnel**: Xray `dns` app + a `dokodemo-door`/tun-level redirect of :53 to Xray's internal DNS (Xray supports `sniffing`+`dns` outbound; needs verification on the TUN inbound path since it is new).
4. **Underlying-network rebinding**: sing-box's `resetNetwork()/updateDefaultInterface` → in Xray we must restart or rely on protected sockets re-dialing; behaviour on Wi-Fi↔cellular must be re-verified on device.
5. **Statistics stream**: poll stats API each second (existing UI contract unchanged).
6. **Hot reload**: none → restart (already supported by state machine).
7. **TUIC**: lost. **Hysteria v1**: lost. Both would be stored but flagged unsupported (parsers already produce them).
8. **Shadowsocks-2022**: lost *if* we build without `sagernet/sing` for licensing (§3.7).
9. Gained: XHTTP, mKCP, VLESS PQ encryption, ECH, Finalmask.

### 4.4 Tests to rewrite / add

- Rewrite: 16 generator tests → Xray generator.
- New: route computation tests (~10), Go binding unit tests (Go), instrumentation smoke test for TUN fd handoff.
- Unchanged: 17 manager tests, 43 parser tests.

### 4.5 Complexity estimate

| Work item | Effort (focused engineer) |
|---|---|
| Go binding + CI build | 3–4 days |
| Xray config generator + tests | 3–4 days |
| Kotlin adapter (start/stop/stats/logs/protect) | 2–3 days |
| TUN route/DNS logic moved into `core/vpn` + tests | 3–5 days |
| Device stabilisation (network change, UDP, DNS, IPv6) | 5–10 days, **needs devices** |
| Docs/licensing hygiene (SBOM, notices, custom distro without GPL deps) | 1–2 days |
| **Total** | **≈ 3–5 weeks** before feature parity with today's Phase 1, excluding lost protocols |

Risk multipliers: Xray's TUN inbound is 8 months old and explicitly labelled
"for network professionals"; libXray tracks pre-release tags; Hysteria2 in
Xray is new. Expect upstream churn.

---

## 5. Product requirements evaluation

| Requirement | A) sing-box | B) Xray-core |
|---|---|---|
| VLESS / VMess / Trojan / SS / SOCKS / HTTP | ✅ | ✅ (VLESS strongest) |
| Hysteria 1 | ✅ | ❌ |
| Hysteria 2 | ✅ mature | ✅ new (Jan 2026) |
| TUIC | ✅ | ❌ |
| WireGuard | ✅ endpoint | ✅ outbound |
| QR / subscription parsing | our code (`core/config`) — core-independent | same |
| TUN/VPN | ✅ sing-tun, mixed stack, auto/strict route | ⚠️ experimental fd TUN, gVisor only, routing by us |
| UDP | ✅ | ✅ (full-cone) |
| DNS (typed servers, hijack, fake-ip, underlying-network bound local resolver) | ✅ all | ✅ servers/fakedns; ⚠️ hijack + local-binding by us |
| Routing (domain/IP/CIDR/rule-set) | ✅ + binary rule-sets | ✅ + geosite/geoip .dat (must ship/download data files) |
| Split tunnelling / per-app | ✅ package rules in core + Builder | ⚠️ Builder-level only |
| Kill switch | strict_route + always-on (documented limitation) | always-on only |
| Smart selection / failover / auto-reconnect | our manager + libbox `urlTest` | our manager + `observatory`/`pingBatch` |
| Background operation | ✅ (implemented) | ✅ (same service code) |
| Real traffic statistics | ✅ push stream | ✅ polled |
| Xray-only transports (XHTTP, mKCP) | ❌ flagged | ✅ |

---

## 6. Decision matrix

| Dimension | A) sing-box/libbox | B) Xray-core |
|---|---|---|
| **Licensing** | GPLv3 + name/association clause; whole APK very likely GPL | MPL-2.0 file-level; **contains GPL `sagernet/sing` unless custom-built without SS-2022**; one LGPL dep |
| **Commercial distribution flexibility** | Selling a GPL app is allowed; keeping app code *closed* is very likely not | Closed app code allowed (Larger Work) — conditional on §3.7 mitigation |
| **What we must publish** | Full app source per release (or written offer), notices, reproducible libbox build | Xray source for the exact commit if modified (we would not modify), notices/SBOM; our code stays private |
| **Android integration** | libbox designed for Android VPN apps (PlatformInterface, TunOptions, protect, interface monitor, local DNS) | fd-based TUN + protect controller; the rest is ours |
| **Protocol coverage** | 10/10 required | 8/10 (no TUIC, no Hy1); gains XHTTP/mKCP |
| **TUN/VPN maturity** | high | low–medium (2026 feature) |
| **DNS** | complete in core | servers in core; hijack/binding in our code |
| **Routing** | rules + per-app in core | rules in core; per-app Builder-only |
| **UDP** | ✅ | ✅ |
| **Performance** | system TCP stack + gVisor UDP; mature | gVisor for everything on TUN path (slightly more CPU); Xray's proxying itself is fast |
| **APK size** | ~86 MB debug (3 ABIs) incl. tailscale/naive bloat — can be trimmed with custom tags | expected similar or somewhat smaller |
| **Maintenance** | one stable upstream cadence; API changes 1.13→1.14 documented in VPN_ENGINE.md | pre-release-heavy cadence; libXray "latest only"; we own more glue code |
| **Security** | uTLS/Reality via sagernet forks; single native lib | Reality reference impl; PQ VLESS; two-layer TUN if tun2socks used |
| **Development effort from today** | 0 to continue | ≈3–5 weeks to parity + device time |
| **Current project compatibility** | 100 % | model/engine-api/config/vpn/app largely preserved (design goal achieved); engine + generator replaced |
| **Future extensibility** | new sing-box features arrive in config JSON only | new Xray features likewise; TUN/DNS/route evolutions are on us |
| **What it restricts** | proprietary app code; marketing wording | TUIC/Hy1/SS-2022; relies on young TUN |
| **What we must build** | Phases 2b–9 as planned | Phases 2b–9 **plus** §4.3 items |
| **What we must maintain** | Kotlin app + generator + 726-line binding | Kotlin app + generator + Kotlin binding + **Go binding** + route/DNS logic + custom Xray distro build |

---

## 7. Recommendations

### 7.1 TECHNICAL recommendation — keep sing-box/libbox

For *this* architecture and *this* feature list, sing-box is the better fit and it is
already integrated, built and CI-verified:
- It is the only option covering all 10 required protocols.
- TUN, routing, DNS hijack, per-app rules, underlying-network handling and
  strict-route are solved inside the core, which is why Phase 1 needed only
  ~700 lines of binding code.
- Xray's TUN inbound is eight months old, self-described as expert-only, and
  pushes ~4 subsystems back onto us; that is precisely the work we chose the
  core to avoid, and it requires device time we currently do not have.

If we stay, two engineering follow-ups are recommended regardless: (a) build libbox
with a **trimmed tag set** (drop `with_tailscale`, `with_naive_outbound`,
`with_clash_api` if unused) to cut size; (b) keep every UI/domain module free of
libbox symbols (already true) so §7.3 remains open.

### 7.2 LICENSING / PRODUCT recommendation — for a proprietary commercial app, sing-box is very probably the wrong core

If the business goal is a **commercial Android app whose own code stays
proprietary**:
- With libbox in the APK, the FSF-style combined-work reading (§2.3) means
  the whole app should be assumed GPLv3. Selling it is fine; keeping the
  source closed is very likely **not**. This is [ENGINEERING INTERPRETATION]
  with a strong consensus behind it, but it is ultimately [LEGAL COUNSEL].
- The separate-process/separate-APK "aggregate" design is legally uncertain and
  product-hostile.
- Xray-core under MPL-2.0 permits a proprietary Larger Work — **but only after
  the `sagernet/sing` GPL dependency is removed from our build** (custom distro
  without `shadowsocks_2022`/`singbridge`, verified by `go list -deps`), and after
  counsel has looked at the remaining LGPL (`juju/ratelimit`) item. Without that
  step, migrating to Xray would *not* actually remove GPL code from the APK.

**Therefore: the answer depends on an unresolved legal interpretation and on a
product decision the repository cannot make**:

| If the product decision is… | then… |
|---|---|
| Open-source app (GPLv3), possibly paid/commercial | **Keep sing-box.** Add license screen + source offer. Continue Phase 2b immediately. |
| Proprietary app code is a hard requirement | **Plan a migration to Xray-core** (with the §3.7 mitigation), obtain counsel sign-off on the SBOM, accept loss of TUIC/Hysteria-1/SS-2022 and a 3–5-week delay plus device testing. Do **not** start Phase 2b UI work that assumes sing-box-only capabilities (XHTTP flagged as unsupported etc.) before deciding. |
| Undecided | Do §7.3 now; it costs little and keeps both doors open. |

### 7.3 Low-regret path while the decision is pending (no migration, no deletion)

1. Keep all new modules (`core/smart`, `core/data`, UI) **100 % free of libbox
   and of `core/singbox-config` imports**; they must talk only to `engine-api`.
   (A Gradle/`konsist`-style architecture test can enforce this.)
2. Invert the `core/vpn → core/engine-singbox` dependency (move the two
   registry hooks into `engine-api`), so `core/vpn` becomes core-agnostic.
3. Register the `CoreAdapter` via a **product flavour** (`singbox` today;
   `xray` later) so an Xray engine can be added side-by-side without
   touching the app when/if the decision goes that way.
4. Add the open-source licences screen and NOTICE generation now — required in
   both futures.

These steps are ordinary hygiene, not a migration, and are compatible with the
"do not remove sing-box / do not add Xray" constraint.

---

## 8. Files inspected

Repository: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`,
`core/*/build.gradle.kts`, `core/engine-singbox/consumer-rules.pro`,
`core/engine-singbox/libbox.sha256`, `core/engine-singbox/singbox.version`,
`core/engine-singbox/src/main/kotlin/io/ucc/core/singbox/android/*.kt` (6 files),
`core/singbox-config/src/main/kotlin/io/ucc/core/singbox/*.kt`,
`core/engine-api/src/main/kotlin/io/ucc/core/engine/**` , `core/vpn/src/main/**`,
`app/src/main/kotlin/io/ucc/app/di/AppGraph.kt`, `LICENSE`,
`.github/workflows/{android-ci,libbox}.yml`, `tools/build-libbox.sh`.

Upstream (GitHub API, 2026-09-16): `SagerNet/sing-box@v1.13.21` `LICENSE`, `go.mod`,
`cmd/internal/build_libbox/main.go`; `LICENSE` of `SagerNet/{sing,sing-tun,sing-quic,sing-vmess,sing-shadowsocks2,sing-mux,cronet-go,sing-shadowsocks}`;
repository license metadata for `SagerNet/{quic-go,gvisor,wireguard-go,gomobile}`, `metacubex/utls`;
`XTLS/Xray-core@v26.3.27` `LICENSE`, `go.mod`, `proxy/` and `transport/internet/` and `app/` listings,
`proxy/tun/{README.md,tun_android.go,tun.go,config.proto}`, `proxy/hysteria/{client.go,config.proto}`,
`transport/internet/hysteria/config.proto`, `common/singbridge/dialer.go`, tags/releases list, code search for `github.com/sagernet/sing`;
`XTLS/libXray` `README.md`, `go.mod`, license; license metadata for `2dust/AndroidLibXrayLite`, `heiher/hev-socks5-tunnel`, `xjasonlyu/tun2socks`.

## 9. Build / test status at audit time

- Last CI run on `337d414`: **success** — `test testDebugUnitTest` (76 tests: engine-api 17, singbox-config 16, config 43), `:app:assembleDebug`, `:app:lintDebug`; artifacts `app-debug`, `libbox-aar`, `reports`.
- No production code modified by this audit. Device verification: NOT AVAILABLE.

---

## 6. Release-pass addendum (v1.0.0, added during Release Engineering)

Evidence from the actual release artifact (see CI step "Inspect release artifacts"):

| dependency | version | licence | in release artifact? | notice required? | where provided |
|---|---|---|---|---|---|
| sing-box / libbox (`libgojni.so` per ABI + `io.nekohasekai.libbox` classes) | v1.13.21 | GPL-3.0-or-later + naming clause | **yes** (native + dex) | yes — full licence text + copyright + source offer | `LICENSE`, `THIRD_PARTY_NOTICES.md` §1, in-app licences |
| sing, sing-tun, sing-quic, sing-vmess, sing-shadowsocks2, sing-mux, cronet-go (statically linked into libbox) | per sing-box go.mod at the tag | GPL-3.0-or-later | yes (inside `libgojni.so`) | yes | `THIRD_PARTY_NOTICES.md` §1 |
| quic-go (sagernet fork), wireguard-go, gVisor, utls, tailscale, gomobile | per go.mod | MIT / MIT / Apache-2.0 / BSD-3 / BSD-3 / BSD-3 | yes (inside `libgojni.so`) | yes (attribution) | `THIRD_PARTY_NOTICES.md` §1 |
| Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization | 2.2.21 / 1.10.2 / 1.9.0 | Apache-2.0 | yes | attribution | notices §2 |
| AndroidX (core, appcompat, activity, lifecycle, navigation, datastore, work, camera-*) | catalog | Apache-2.0 | yes | attribution | notices §2 |
| Jetpack Compose (BOM 2025.09.01), Material 3, material-icons-extended | BOM | Apache-2.0 | yes | attribution | notices §2 |
| ML Kit barcode-scanning + transitive play-services-basement / mlkit common / vision-common / barcode-scanning-common | 17.3.0 / 18.4.0 / 18.11.0 / 17.3.0 / 17.0.0 | Android SDK Licence + ML Kit ToS (proprietary) | **yes** | terms notice | notices §3, in-app |
| Room, security-crypto, OkHttp, Turbine, Robolectric, MockWebServer | catalog only | — | **no** (not applied/test-only) | no | — |

**Legal-review items that remain open** (technical evidence cannot close them):

1. **Corresponding source offer.** Distributing the APK/AAB triggers GPLv3 §6. The
   application source is itself GPL-3.0-or-later, but the repository is private at the
   time of writing. Before any public distribution the repo must be public (or a written
   offer provided), including `tools/build-libbox.sh` and the pinned tag so the exact
   `libgojni.so` can be rebuilt.
2. **Proprietary ML Kit binary inside a GPL-licensed APK.** Whether the aggregate is
   permissible under GPLv3 §5/§7 (system-library exception does not obviously apply to a
   bundled Play-services library) needs counsel. Technical fallback exists: ZXing
   (Apache-2.0) could replace ML Kit; not done in this pass.
3. **sing-box naming clause.** App name, package (`io.ucc.app`) and icon do not contain
   "sing-box"; UI shows the core name only as factual attribution and a non-affiliation
   statement. Counsel should confirm this satisfies "no derivative work may … imply association".
4. **Licence text delivery inside the artifact.** `LICENSE` and notices are rendered in-app
   from `Notices.kt`; the full GPL text is shown from a bundled copy. Confirm this is an
   acceptable §4/§5 notice mechanism for the store build (many GPL Android apps do the same).
