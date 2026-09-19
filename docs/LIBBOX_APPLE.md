# Libbox for Apple — build pipeline (KMP Phase 5)

Phase 5 adds the **build and verification pipeline** for `Libbox.xcframework`, the
Apple binding of the sing-box core that the future iOS Network Extension (Phase 6)
will link against. Nothing in this phase changes Android, Smart Selection,
`core/app-logic`, `core/ios-infra`, or any serialization/encryption format.

## Pinned inputs (single source of truth, shared with Android)

| Pin | File | Value |
|---|---|---|
| sing-box tag | `core/engine-singbox/singbox.version` | `v1.13.21` |
| sing-box commit | `core/engine-singbox/singbox.commit` | `628cb31ffa79cffffd34c2f9cde6cae044e4fc12` |
| gomobile (SagerNet fork) | upstream `Makefile` `lib_install`, re-checked by the script | `v0.1.12` |
| Go minimum | upstream `go.mod` | `1.24.7` (CI uses 1.25.x) |
| Android `libbox.aar` SHA-256 | `core/engine-singbox/libbox.sha256` | `ff2890c1…da9a` (unchanged) |
| Apple `Libbox.xcframework.zip` SHA-256 | `core/engine-singbox/libbox-apple.sha256` | see file (pinned after the first reviewed macOS build) |

Version bumps must change `singbox.version` **and** `singbox.commit` together; the
script and workflow refuse a tag that resolves to a different commit.

## Build path (upstream, no fork)

`tools/build-libbox-apple.sh` is a fail-fast macOS script that runs exactly what
upstream runs for its own Apple apps at v1.13.21:

```
make lib_install                                  # go install sagernet/gomobile + gobind @v0.1.12
go run ./cmd/internal/build_libbox -target apple -platform ios,iossimulator
```

Build tags come from upstream `cmd/internal/build_libbox` (with_gvisor, with_quic,
with_wireguard, with_utls, with_naive_outbound, with_clash_api, with_tailscale…,
darwin: with_dhcp, grpcnotrace; `-tags-not-macos=with_low_memory`), so the iOS
core has the same feature set as the official sing-box iOS app. The Android
`libbox.aar` is built by the sibling `tools/build-libbox.sh` from the same tag.

The script checks: macOS + Xcode (`xcodebuild`, `xcrun --sdk iphoneos`), Go ≥ go.mod,
tag → commit, clean checkout, gomobile version in Makefile == expected == go.mod,
then validates the output:

* `Info.plist` parses; every requested platform has its slice (ios device arm64,
  ios simulator arm64[/x86_64]);
* each slice has `Libbox` binary, `Headers/`, `Modules/module.modulemap`;
* `lipo -archs` of each binary equals the plist's `SupportedArchitectures`;
* deterministic zip (sorted entries, fixed mtimes) → `Libbox.xcframework.zip`,
  `Libbox.xcframework.zip.sha256`, `PROVENANCE.txt` (sing-box version + commit,
  Go, gomobile, Xcode, macOS versions, UTC timestamp, output SHA-256, slices).

Optional: `LIBBOX_EXPECTED_SHA256=<hex>` makes the script fail on mismatch;
`LIBBOX_PLATFORMS=ios,iossimulator,macos` etc. extends targets.

## CI: `.github/workflows/libbox-apple.yml`

* `macos-15` runner, checkout of the exact commit (`ref: github.sha`);
* verifies upstream `refs/tags/v1.13.21` still points at the pinned commit;
* Go 1.25.x via `actions/setup-go`, module cache keyed by tag + script hash;
* runs the script, compares the SHA-256 with `libbox-apple.sha256`
  (`unpinned` → warning, mismatch → failure);
* publishes `PROVENANCE.txt` in the job summary **and** as the
  `libbox-apple-provenance` check-run on the commit, then uploads
  `Libbox.xcframework.zip` + `.sha256` + `PROVENANCE.txt` as artifact
  `libbox-xcframework-v1.13.21` (90 days).
* No signing, notarization, TestFlight or App Store steps.

Triggered by changes to the pins, the script, or the workflow, and manually.

## How the framework is consumed

* The binary is **not committed** (`.gitignore` already excludes `build/`; the repo
  never vendored the Android AAR either — it is served from the Actions cache).
* Later iOS builds (Phase 6+) download the `libbox-xcframework-<tag>` artifact of a
  green `libbox-apple` run, verify `shasum -a 256 -c Libbox.xcframework.zip.sha256`
  **and** compare with `core/engine-singbox/libbox-apple.sha256`, unzip, and link
  the framework from the Xcode/K/N project. A mismatch is a stop condition.
* Local developers run `tools/build-libbox-apple.sh` on macOS and get the same
  layout under `build/libbox-apple/`.

## Adapter boundary decision (no code added in Phase 5)

The existing boundaries stay: UI → `CoreManager` → `CoreAdapter` ← `CoreFactory(CorePlatform)`.
An Apple `SingBoxCoreFactory`/`CoreAdapter` would be a Kotlin/Native module with a
**cinterop against `Libbox.xcframework`**. Kotlin/Native cannot compile a cinterop
definition without the framework present, and Linux CI has no framework, so such a
module cannot be built or verified in this repository today. A stub module without
libbox would be a fake adapter — explicitly out of policy. Therefore:

* `core/engine-singbox-apple` is **deferred to Phase 6**, where it is created together
  with the artifact download step in a macOS job, and must implement `CoreFactory`,
  `CoreAdapter` and consume `CorePlatform` exactly like the Android
  `io.ucc.core.singbox.android.SingBoxCoreFactory`.
* `core/singbox-config` (already KMP, generates the sing-box JSON) is the shared
  config layer both adapters use; no Apple-specific config work was needed.
* Boundary script: libbox symbols remain confined to `core/engine-singbox*`
  modules (`io.nekohasekai.libbox` / `import go.` rule already covers `core/`).

## Licences

`Libbox.xcframework` contains the same components as `libbox.aar`
(sing-box and sing-* GPL-3.0-or-later with the sing-box naming clause; quic-go,
wireguard-go MIT; gVisor Apache-2.0; utls, tailscale, gomobile BSD-3-Clause). See
`docs/CORE_LICENSE_AUDIT.md`; the iOS app must ship the same notices
(`SingBoxNotices` equivalent) when Phase 6 wires the adapter.

## What Linux CI can and cannot verify (honest limits)

* Linux (`android-ci.yml`): Android tests/builds, KMP JVM tests, iOS **klib
  compilation** of the Kotlin modules, boundary/secret/notice checks.
* macOS (`libbox-apple.yml`): building the framework, structure/architecture
  validation, hashing, provenance.
* **Not verified anywhere yet:** linking the framework into an app or extension,
  running the core on an iOS device/simulator, packet-tunnel behaviour. That is
  Phase 6 work and requires a macOS host with Xcode and a device/simulator.

## Required local environment

macOS 14+ on Apple silicon or Intel, Xcode 16.x with the iOS SDK (licence accepted),
Go ≥ 1.24.7 (1.25 recommended), git, zip, python3. First build downloads ~1 GB of Go
modules and takes 10–25 minutes.
