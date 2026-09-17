# Third-party notices — Universal Connection Client

This file lists the third-party components **distributed with the application
binary** (APK/AAB), their licenses and where their source lives. It is the
authoritative inventory; the in-app screen (Settings → About → Open-source
licenses) summarises the same list. Test-only and build-only tools (JUnit,
Robolectric, Turbine, MockWebServer, AGP, KSP) are not distributed and are not
listed here.

Universal Connection Client itself is licensed **GPL-3.0-or-later** — see
[`LICENSE`](LICENSE). It comes with ABSOLUTELY NO WARRANTY.

Universal Connection Client is an independent work. It is **not affiliated
with, endorsed by, or associated with** the sing-box project, SagerNet or any
other upstream listed below; their names appear only as factual attribution
required by their licenses.

Sources consulted: `gradle/libs.versions.toml`, Maven POM metadata
(dl.google.com / Maven Central), upstream `LICENSE` files at the pinned tags
(see `docs/CORE_LICENSE_AUDIT.md` §1.9 and §2.1 for the sing-box tree).

---

## 1. Native core: sing-box / libbox

| Component | Version | License | Source |
|---|---|---|---|
| **sing-box** (compiled to `libbox.aar` by `tools/build-libbox.sh`; libbox is the gomobile binding that ships inside sing-box's `experimental/libbox` package) | **v1.13.21** (`SingBoxCapabilities.PINNED_TAG`) | **GPL-3.0-or-later** + additional term below | https://github.com/SagerNet/sing-box |

Upstream copyright and notice, reproduced verbatim from `LICENSE` at tag v1.13.21:

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Compliance notes:
- `libbox.aar` is built unmodified from the tagged upstream source by CI
  (`.github/workflows/libbox.yml`); the exact build script and tags are in
  `tools/build-libbox.sh`. Corresponding Source is therefore the upstream tag
  plus this repository.
- The full GPL-3.0 text is shipped in the APK (`assets/licenses/GPL-3.0.txt`)
  and in this repository (`LICENSE`).
- The naming clause is honoured: the product name, icon and store copy do not
  use "sing-box", "SFA" or "SagerNet" and do not claim association.

### 1.1 Go modules linked into libbox (from `sing-box/go.mod` at v1.13.21)

Versions follow the pinned tag's `go.mod`; licenses were read from each
repository's `LICENSE` file.

| Module | License | Source |
|---|---|---|
| github.com/sagernet/sing | GPL-3.0-or-later | https://github.com/SagerNet/sing |
| github.com/sagernet/sing-tun | GPL-3.0-or-later | https://github.com/SagerNet/sing-tun |
| github.com/sagernet/sing-quic | GPL-3.0-or-later | https://github.com/SagerNet/sing-quic |
| github.com/sagernet/sing-vmess | GPL-3.0-or-later | https://github.com/SagerNet/sing-vmess |
| github.com/sagernet/sing-shadowsocks, sing-shadowsocks2 | GPL-3.0-or-later | https://github.com/SagerNet/sing-shadowsocks2 |
| github.com/sagernet/sing-mux | GPL-3.0-or-later | https://github.com/SagerNet/sing-mux |
| github.com/sagernet/sing-shadowtls | GPL-3.0-or-later | https://github.com/SagerNet/sing-shadowtls |
| github.com/sagernet/cronet-go | GPL-3.0-or-later | https://github.com/SagerNet/cronet-go |
| github.com/sagernet/quic-go (fork of quic-go) | MIT | https://github.com/SagerNet/quic-go |
| github.com/sagernet/wireguard-go (fork of wireguard-go) | MIT | https://github.com/SagerNet/wireguard-go |
| github.com/sagernet/gvisor (fork of gVisor) | Apache-2.0 | https://github.com/SagerNet/gvisor |
| github.com/sagernet/tailscale (fork; `with_tailscale`) | BSD-3-Clause | https://github.com/SagerNet/tailscale |
| github.com/sagernet/gomobile (binding generator) | BSD-3-Clause | https://github.com/SagerNet/gomobile |
| github.com/metacubex/utls (fork of uTLS) | BSD-3-Clause | https://github.com/metacubex/utls |
| Go standard library / runtime | BSD-3-Clause | https://go.dev |

The complete transitive Go module list is in the upstream `go.sum` at the
pinned tag; only direct, licensing-relevant modules are enumerated here.

---

## 2. Android / Kotlin runtime dependencies (`app`, `core/*` modules)

| Component | Version | License | Source |
|---|---|---|---|
| Kotlin standard library | 2.2.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines (core, android) | 1.10.2 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization (json) | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.appcompat:appcompat | 1.7.1 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.lifecycle (runtime-ktx, runtime-compose, viewmodel-compose, service, process) | 2.9.4 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.navigation:navigation-compose | 2.9.5 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.datastore:datastore-preferences | 1.1.7 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.work:work-runtime-ktx | 2.10.5 | Apache-2.0 | https://github.com/androidx/androidx |
| Jetpack Compose: ui, ui-graphics, ui-tooling-preview, material3, material-icons-extended | BOM 2025.09.01 | Apache-2.0 | https://github.com/androidx/androidx |
| androidx.camera: camera-core, camera-camera2, camera-lifecycle, camera-view | 1.5.1 | Apache-2.0 | https://github.com/androidx/androidx |
| Material Components for Android (transitive via AppCompat / Compose) | per AndroidX POMs | Apache-2.0 | https://github.com/material-components/material-components-android |
| com.google.mlkit:barcode-scanning (bundled on-device model) | 17.3.0 | **ML Kit Terms of Service** (proprietary; declared in the artifact POM) | https://developers.google.com/ml-kit/terms |
| ML Kit transitive: com.google.mlkit:common 18.11.0, vision-common 17.3.0, barcode-scanning-common 17.0.0, com.google.android.gms:play-services-mlkit-barcode-scanning 18.3.1, play-services-basement 18.4.0 | as listed (from barcode-scanning-17.3.0.pom) | ML Kit Terms of Service / Android Software Development Kit License | https://developer.android.com/studio/terms |

Full license texts:
- GNU GPL-3.0: [`LICENSE`](LICENSE) and `app/src/main/assets/licenses/GPL-3.0.txt`
- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT, BSD-3-Clause: see each repository's `LICENSE` file linked above.

Notes:
- Room and security-crypto are declared in the version catalog but are **not**
  applied to any module; they are not distributed.
- No analytics, advertising or crash-reporting SDKs are included.
- Networking for subscriptions uses `java.net.HttpURLConnection` (Android
  platform). OkHttp is declared in the catalog but not applied to any module.

Maintenance: update this file, `app/.../data/Notices.kt`, and
`core/engine-singbox/.../SingBoxNotices.kt` together whenever
`gradle/libs.versions.toml` or the pinned sing-box tag changes.
