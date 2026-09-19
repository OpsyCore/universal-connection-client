# Corresponding-source release — Android v1.0.0

> Written for the v1.0.0 release candidate at commit `64563ba`. Marks with
> **[LEGAL]** every point that needs a lawyer's confirmation; nothing here is a
> legal guarantee of compliance.

## 1. Why this document exists
`libbox.aar` (the sing-box core, GPL-3.0-or-later plus a naming clause) is
compiled into the app. Distributing the APK/AAB therefore triggers GPLv3 §6:
the distributor must convey the *complete corresponding source* — the app's
own source, the exact sing-box source used, and the scripts that control
building the binary — or make a written offer for it. The app itself is
licensed GPL-3.0-or-later (`LICENSE`), so there is no licence conflict inside
the Kotlin code. **[LEGAL]** confirm the conveyance method chosen in §5.

## 2. Release identity
| item | value |
|---|---|
| Release tag (to be created — see `ANDROID_V1_RELEASE_CHECKLIST.md`) | `v1.0.0` |
| Source commit at tag | *(filled when the tag is cut; must equal the commit in the release build's `PROVENANCE.txt`)* |
| applicationId / versionName / versionCode | `io.ucc.app` / `1.0.0` / `1` |

## 3. Core provenance (Android)
| item | value | where pinned |
|---|---|---|
| sing-box version | `v1.13.21` | `core/engine-singbox/singbox.version` |
| sing-box commit | `628cb31ffa79cffffd34c2f9cde6cae044e4fc12` | `core/engine-singbox/singbox.commit` |
| libbox.aar SHA-256 | `ff2890c1af43f2e39e9136a8e03509ec40b7c278ab5549496be506758918da9a` | `core/engine-singbox/libbox.sha256` — verified at Gradle configuration time and in CI (build fails on mismatch) |
| Build recipe | `tools/build-libbox.sh` → `make lib_install` (SagerNet gomobile v0.1.12) → `go run ./cmd/internal/build_libbox -target android` on the tag above | `.github/workflows/libbox.yml` |
| Toolchain | Go 1.25.x, Android NDK r28, JDK 17 | workflow file |
| Apple Libbox | not part of v1.0.0 (iOS deferred to v1.1); `libbox-apple.sha256` = `unpinned` | `docs/IOS_LIBBOX.md` |

Note: libbox.aar is a **build output**, not committed; CI rebuilds it from the
pinned tag and enforces the hash. Anyone with the source above and the recipe
can reproduce the same input the app was built from (bit-identical output is
not guaranteed by gomobile; the hash pin identifies the artefact actually
shipped).

## 4. What the corresponding source consists of
1. This repository at tag `v1.0.0` (Kotlin app, all `core/*` modules, Gradle build, ProGuard/R8 rules, CI workflows, `tools/`).
2. sing-box source at tag `v1.13.21` / commit `628cb31f…` — upstream `https://github.com/SagerNet/sing-box`; the repo does not vendor it, it pins it (§3). **[LEGAL]** decide whether pinning + public upstream suffices or whether a copy of the sing-box tarball must be archived alongside the release (safer: attach `sing-box-1.13.21-source.tar.gz` to the GitHub release).
3. The exact `libbox.aar` used (attach to the GitHub release with its SHA-256) so that the binary in the APK can be matched to the source.
4. Go module dependencies are resolved by `go.mod`/`go.sum` of the sing-box tag (listed in `THIRD_PARTY_NOTICES.md` §1.1).

Not part of the corresponding source (allowed as "System Libraries"/separately
licensed binaries **[LEGAL]**): Android SDK/AndroidX (Apache-2.0), Google ML Kit
barcode scanning (proprietary binary, Google ML Kit ToS).

## 5. How the source is made available — decision required
Options (choose one; ☐ owner action, cannot be done from the CI integration):

| option | steps | notes |
|---|---|---|
| **A. Public repository** (simplest) | ☐ Make `OpsyCore/universal-connection-client` public **before** the store listing goes live; ☐ create GitHub release `v1.0.0` with the AAB/APK SHA-256, `libbox.aar`, sing-box source tarball, `THIRD_PARTY_NOTICES.md`. | The repo is private today. Changing visibility requires the repository owner in GitHub settings; the CI token cannot do it. Review the git history for anything that must not be public first (secret scan is green; no keystores were ever committed). |
| **B. Written offer** | ☐ Keep the repo private; include in the store listing and in the app's Licences screen a written offer valid ≥ 3 years to provide the corresponding source on request; ☐ keep a tagged source archive + libbox.aar + sing-box tarball for that period. | More bookkeeping; **[LEGAL]** wording of the offer. |

Until one option is executed, the app **must not be published**.

## 6. Notices that ship with the app
- `LICENSE` — GPLv3 text (root). Bundled in the APK as `assets/licenses/GPL-3.0.txt`; CI (`tools/check-notices.sh` + `inspect-release.sh`) fails if it is missing or differs from `LICENSE`.
- `THIRD_PARTY_NOTICES.md` — sing-box copyright ("Copyright (C) 2022 by nekohasekai"), GPL-3.0-or-later, the naming clause verbatim, every Go module linked into libbox (from the tag's `go.mod`), every Android/Kotlin dependency with licence and URL, ML Kit terms. Mirrored in-app: Settings → Licences (`app/data/Notices.kt`).
- `docs/CORE_LICENSE_AUDIT.md` — the licence analysis behind the core decision.

## 7. sing-box naming clause
> "In addition, no derivative work may use the name or imply association with this application without prior consent."

Compliance measures in place: the product name is "Universal Connection
Client", the icon is original, store copy must not use "sing-box" as a brand
(it may state factually that the app *uses* the sing-box core in the licences
section); the in-app notice states non-affiliation. **[LEGAL]** confirm that a
factual "powered by sing-box" mention in notices is acceptable under the
clause, or drop it from user-facing copy and keep it only in the licence list.

## 8. Google ML Kit
`com.google.mlkit:barcode-scanning:17.3.0` (bundled on-device model) is a
proprietary binary distributed under Google's ML Kit Terms of Service and
listed as such in the notices. It is linked into a GPL-licensed app.
**[LEGAL]** confirm this combination is acceptable for distribution (the common
reading treats it like other proprietary platform SDKs the GPL app calls into,
but it is not a "System Library" in the GPL sense). Fallback if not: replace
with ZXing (Apache-2.0) in a later version — not done for v1.0.0.

## 9. Verification checklist (repo side — all green at `64563ba`)
- [x] `tools/check-notices.sh` — GPL text, sing-box tag, copyright holder, ML Kit terms present.
- [x] `tools/inspect-release.sh` — GPL asset in APK.
- [x] `libbox.sha256` pinned and enforced; `singbox.version`/`singbox.commit` present.
- [x] `tools/check-secrets.sh` — no secrets in the tree.
- [ ] Release tag created and equal to the commit in `PROVENANCE.txt` (deferred — see checklist).
- [ ] Option A or B of §5 executed (owner).
