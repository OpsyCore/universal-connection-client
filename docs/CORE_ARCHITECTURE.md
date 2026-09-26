# Core architecture boundary

Status: implemented after the Core & Licensing audit (`docs/CORE_LICENSE_AUDIT.md` §7.3).
Goal: every module except the engine modules must be **core-agnostic**, so a
second engine (e.g. Xray-core) can be added without touching the Config Engine,
UI, Server Manager, Smart Engine or the VPN service.

## 1. Module graph

### Before (Phase 1)

```
:app ──► :core:engine-singbox  (imports SingBoxCoreAdapter directly in AppGraph)
:app ──► :core:singbox-config
:app ──► :core:vpn ──► :core:engine-singbox      ← VPN layer transitively depended on libbox
all  ──► :core:engine-api ──► :core:model
```

### After

```
                 ┌───────────────── core-agnostic ─────────────────┐
:app/src/main ──►│ :core:vpn ──► :core:engine-api ──► :core:model  │◄── :core:config
                 └──────────────────────────────────────────────────┘
:app/src/singbox (flavour) ──► :core:engine-singbox ──► :core:singbox-config ──► libs/libbox.aar
                                       ▲
                       only module that may import io.nekohasekai.libbox / io.ucc.core.singbox.*
```

- `:core:vpn` no longer depends on any engine module (removed
  `implementation(project(":core:engine-singbox"))`).
- `:app/src/main` has **zero** engine imports. The engine dependency is
  declared with a flavour-scoped configuration
  (`"singboxImplementation"(project(":core:engine-singbox"))`) and the only
  file naming a concrete engine lives in the flavour source set
  `app/src/singbox/kotlin/io/ucc/app/core/CoreFactories.kt`.
- `:core:singbox-config` is engine-specific by nature (it emits sing-box JSON)
  and is reachable only through `:core:engine-singbox`.

## 2. The boundary contracts (`:core:engine-api`, pure Kotlin)

| Type | Role |
|---|---|
| `CoreAdapter` | Runtime contract: `start/reload/stop/onNetworkChanged/onPause/onResume`, `statistics`, `logs`, `events`, `urlTest`, `descriptor`, `capabilities`. Unchanged. |
| `TunProvider` / `TunRequest` | The engine asks the platform for a TUN fd and socket protection. Unchanged. |
| **`CoreFactory`** *(new)* | `id`, `descriptor`, `capabilities`, `notices: List<ThirdPartyNotice>`, `create(CorePlatform): CoreAdapter`. One per engine module. |
| **`CorePlatform`** *(new)* | Host services handed to an engine: `workingDirectory`, `cacheDirectory`, `debug`, `underlyingNetwork: StateFlow<UnderlyingNetwork?>`. Engines may not ask for anything else. |
| **`UnderlyingNetwork`** *(new)* | Platform-neutral description of the non-VPN network (opaque `handle`, interface name/index, metered flag). |
| **`InterfaceObserver`** *(new, optional)* | Engines that need underlying-interface updates implement it; the platform detects it with `as? InterfaceObserver`. Engines that do not need it ignore it. |
| **`ThirdPartyNotice`** *(new)* | Licence entry (component, version, licence, URL, additional terms). Engines carry their own list. |
| `ConnectionManager` / `DefaultConnectionManager` | Single source of truth; depends on `CoreAdapter` only. Unchanged. |

## 3. Selection & wiring

```
app/build.gradle.kts
  flavorDimensions += "core"
  productFlavors { create("singbox") { buildConfigField("String", "CORE_ID", "\"singbox\"") } }
  dependencies    { "singboxImplementation"(project(":core:engine-singbox")) }

app/src/singbox/kotlin/io/ucc/app/core/CoreFactories.kt
  available(context) = listOf(SingBoxCoreFactory(context))
  selected(context)  = available().first { it.id == BuildConfig.CORE_ID }

app/src/main/kotlin/io/ucc/app/di/AppGraph.kt
  coreFactory  = CoreFactories.selected(app)
  corePlatform = AndroidCorePlatform(app, BuildConfig.DEBUG, networkMonitor)
  core         = coreFactory.create(corePlatform)
  (core as? InterfaceObserver)?.let { networkMonitor.interfaceObserver = it }
  notices      = Notices(coreFactory)
  connectionManager = DefaultConnectionManager(scope, core, AndroidTunnelHost(app), profileStore, networkMonitor, clock)
```

Gradle tasks are now flavour-qualified: `assembleSingboxDebug`,
`testSingboxDebugUnitTest`, `lintSingboxDebug` (CI updated).

## 4. Adding another engine later (e.g. Xray) — what it would take

1. New module `:core:engine-xray` (Android lib) + `:core:xray-config` (JVM): implements
   `CoreFactory` + `CoreAdapter`; may implement `InterfaceObserver`; provides its own
   `ThirdPartyNotice` list (MPL-2.0 etc.).
2. `app/build.gradle.kts`: `create("xray") { buildConfigField(... "xray") }` and
   `"xrayImplementation"(project(":core:engine-xray"))`.
3. `app/src/xray/kotlin/io/ucc/app/core/CoreFactories.kt` listing `XrayCoreFactory`.
4. Nothing else changes: `core/model`, `core/engine-api`, `core/config`, `core/vpn`,
   `app/src/main` (UI, ViewModels, stores, `AppGraph`) are untouched.
   The 17 manager tests + 43 parser tests keep covering the new engine's host logic.

What an Xray adapter would additionally have to do internally (from the audit §4.3):
route/exclusion computation for the TUN request, DNS hijack, stats polling. These
live entirely inside the engine module, behind `CoreAdapter`.

## 5. Notices infrastructure

- Each `CoreFactory.notices` lists the licences compiled into that engine
  (`SingBoxNotices` for libbox: sing-box GPL-3.0-or-later **with** its
  naming/association clause, sing-* GPL-3.0-or-later, gVisor Apache-2.0, quic-go/wireguard-go MIT, utls/gomobile/tailscale BSD-3).
- `io.ucc.app.data.Notices` merges application-level notices (Kotlin/AndroidX,
  Apache-2.0) with the selected engine's and renders plain text for the future
  Settings → "Open-source licences" screen and for `THIRD_PARTY_NOTICES.txt`
  in release artefacts (Phase 9). No UI screen exists yet; the data path does.

## 6. Enforcement

`tools/check-core-boundary.sh` (also a CI step before the tests) fails the build if:
- any file outside `core/engine-singbox` mentions `io.nekohasekai.libbox` or `import go.`;
- `core/model`, `core/engine-api`, `core/config`, `core/vpn` or `app/src/main` import `io.ucc.core.singbox.*`;
- an engine module is wired into a core-agnostic module's Gradle file, or into `app` with a non-flavour configuration.

`CoreFactoryContractTest` (engine-api) proves a manager can be assembled from a
`CoreFactory` + `CorePlatform` with no engine type in scope, and that two
factories are interchangeable with their own notices.

## 7. Remaining sing-box coupling (intentional)

| Where | What | Why it is acceptable |
|---|---|---|
| `core/engine-singbox` (7 files) | libbox API | this *is* the engine module |
| `core/singbox-config` | sing-box JSON | engine-specific by design; reachable only via the engine module |
| `app/src/singbox/.../CoreFactories.kt` | `SingBoxCoreFactory` | flavour source set; swapped per flavour |
| `app/build.gradle.kts` | `singboxImplementation`, flavour `singbox` | build-time selection point |
| `core/model/Transport.Unsupported` semantics | "unsupported" today means "not in sing-box" | `CoreCapabilities.transports` is the runtime truth; UI must read capabilities, not assume |
| `docs/CONFIG_FORMATS.md`, `VPN_ENGINE.md` | describe sing-box behaviour | docs, not code |

Not coupled but worth noting: the `TunRequest` shape (addresses, routes,
exclude routes, DNS servers, packages, HTTP proxy) was modelled on what
sing-tun computes. An engine that does not compute routes itself must fill
`TunRequest` on its own — the contract allows it; no field is libbox-specific.

## 8. Known architectural debts

- `VpnServiceRegistry` is a process-global object for service ↔ manager
  rendezvous; fine for one process, should become an injected holder when a DI
  framework arrives.
- `AndroidNetworkMonitor` still exposes the legacy `onInterface`/`onInterfaceLost`
  lambdas next to `interfaceObserver`; they are unused now and can be removed in a later cleanup.
- `CorePlatform.underlyingNetwork.handle` is `Any` (on Android an `android.net.Network`) — a deliberate
  trade-off to keep `engine-api` free of Android types.
