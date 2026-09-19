#!/usr/bin/env bash
# Builds Libbox.xcframework (sing-box "libbox" for Apple) from the pinned
# sing-box tag, using upstream's own build path — no fork, no custom bind code:
#
#   make lib_install                              (sagernet/gomobile @ pinned version)
#   go run ./cmd/internal/build_libbox -target apple -platform "$PLATFORMS"
#
# Must run on macOS with Xcode (gomobile needs xcrun/xcodebuild/clang for Apple
# targets). Linux hosts cannot build or run this — see docs/LIBBOX_APPLE.md.
#
#   SING_BOX_TAG          default: core/engine-singbox/singbox.version (shared with Android)
#   SING_BOX_COMMIT       expected commit for the tag (default: core/engine-singbox/singbox.commit);
#                         the checkout must resolve to it or the build aborts
#   LIBBOX_PLATFORMS      gomobile targets (default: ios,iossimulator)
#   LIBBOX_WORK           source checkout dir (default: build/sing-box-src-apple)
#   LIBBOX_OUT            output dir (default: build/libbox-apple)
#   LIBBOX_EXPECTED_SHA256  optional; if set, the zipped framework must match
#
# Outputs (deterministic paths inside $LIBBOX_OUT):
#   Libbox.xcframework/            the framework
#   Libbox.xcframework.zip         deterministic zip (sorted entries, fixed mtimes)
#   Libbox.xcframework.zip.sha256  "<sha256>  Libbox.xcframework.zip"
#   PROVENANCE.txt                 tool versions, source commit, timestamp, hash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${SING_BOX_TAG:-$(tr -d ' \n' < "$ROOT/core/engine-singbox/singbox.version")}"
EXPECTED_COMMIT="${SING_BOX_COMMIT:-$(tr -d ' \n' < "$ROOT/core/engine-singbox/singbox.commit")}"
PLATFORMS="${LIBBOX_PLATFORMS:-ios,iossimulator}"
WORK="${LIBBOX_WORK:-$ROOT/build/sing-box-src-apple}"
OUT="${LIBBOX_OUT:-$ROOT/build/libbox-apple}"
GOMOBILE_VERSION="v0.1.12"   # what `make lib_install` installs in sing-box v1.13.x; re-verified below against the Makefile
MIN_GO="1.24.7"              # sing-box v1.13.21 go.mod

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo ">> $*"; }

# ---------------------------------------------------------------- prerequisites
step "host prerequisites"
[ "$(uname -s)" = "Darwin" ] || die "Libbox.xcframework can only be built on macOS (found $(uname -s))"
command -v xcodebuild >/dev/null || die "xcodebuild not found — install Xcode and run: sudo xcode-select -s /Applications/Xcode.app"
command -v xcrun >/dev/null || die "xcrun not found"
command -v go >/dev/null || die "go not found"
command -v git >/dev/null || die "git not found"
command -v shasum >/dev/null || die "shasum not found"
command -v zip >/dev/null || die "zip not found"
command -v python3 >/dev/null || die "python3 not found"
xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1 || die "iOS SDK not available (xcrun --sdk iphoneos failed); accept the Xcode license: sudo xcodebuild -license accept"

XCODE_VERSION="$(xcodebuild -version | tr '\n' ' ' | sed 's/ *$//')"
MACOS_VERSION="$(sw_vers -productVersion) ($(uname -m))"
GO_VERSION_RAW="$(go version)"
GO_VERSION="$(go env GOVERSION | sed 's/^go//')"
echo "   macOS : $MACOS_VERSION"
echo "   Xcode : $XCODE_VERSION"
echo "   Go    : $GO_VERSION_RAW"

version_ge() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]; }
version_ge "$GO_VERSION" "$MIN_GO" || die "Go >= $MIN_GO required by sing-box $TAG (found $GO_VERSION)"

# ------------------------------------------------------------------- source
step "sing-box source $TAG"
if [ ! -d "$WORK/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/SagerNet/sing-box.git "$WORK"
else
  git -C "$WORK" fetch --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
  git -C "$WORK" checkout -q "$TAG"
fi
ACTUAL_COMMIT="$(git -C "$WORK" rev-parse HEAD)"
echo "   commit: $ACTUAL_COMMIT"
[ "$ACTUAL_COMMIT" = "$EXPECTED_COMMIT" ] || die "tag $TAG resolved to $ACTUAL_COMMIT, expected pinned $EXPECTED_COMMIT (core/engine-singbox/singbox.commit)"
[ -z "$(git -C "$WORK" status --porcelain)" ] || die "source checkout is dirty"

MAKEFILE_GOMOBILE="$(grep -oE 'gomobile/cmd/gomobile@v[0-9.]+' "$WORK/Makefile" | head -n1 | sed 's/.*@//')"
[ -n "$MAKEFILE_GOMOBILE" ] || die "cannot read gomobile version from upstream Makefile"
[ "$MAKEFILE_GOMOBILE" = "$GOMOBILE_VERSION" ] || die "upstream Makefile installs gomobile $MAKEFILE_GOMOBILE but this script expects $GOMOBILE_VERSION — review and update both together"
GOMOD_GO="$(awk '$1=="go"{print $2}' "$WORK/go.mod")"
version_ge "$GO_VERSION" "$GOMOD_GO" || die "go.mod requires go $GOMOD_GO, found $GO_VERSION"

# ------------------------------------------------------------------- gomobile
step "gomobile $GOMOBILE_VERSION (sagernet fork, via upstream 'make lib_install')"
export GOTOOLCHAIN=local GOFLAGS=-mod=mod
GOBIN_DIR="$(go env GOPATH)/bin"
export PATH="$GOBIN_DIR:$PATH"
( cd "$WORK" && make lib_install )
command -v gomobile >/dev/null || die "gomobile not on PATH after install ($GOBIN_DIR)"
command -v gobind >/dev/null || die "gobind not on PATH after install"
GOMOBILE_RESOLVED="$(cd "$WORK" && go list -m -f '{{.Version}}' github.com/sagernet/gomobile)"
echo "   gomobile module in go.mod: $GOMOBILE_RESOLVED"
[ "$GOMOBILE_RESOLVED" = "$GOMOBILE_VERSION" ] || die "go.mod pins gomobile $GOMOBILE_RESOLVED, expected $GOMOBILE_VERSION"

# ---------------------------------------------------------------------- build
step "build Libbox.xcframework for: $PLATFORMS"
rm -rf "$OUT"; mkdir -p "$OUT"
( cd "$WORK" && rm -rf Libbox.xcframework && go run ./cmd/internal/build_libbox -target apple -platform "$PLATFORMS" )
[ -d "$WORK/Libbox.xcframework" ] || die "build finished but Libbox.xcframework is missing"
mv "$WORK/Libbox.xcframework" "$OUT/Libbox.xcframework"
FW="$OUT/Libbox.xcframework"

# ----------------------------------------------------------------- validation
step "validate framework structure"
[ -f "$FW/Info.plist" ] || die "Info.plist missing at xcframework root"
plutil -lint "$FW/Info.plist" >/dev/null || die "Info.plist is not a valid plist"
SLICES="$(python3 - "$FW/Info.plist" <<'EOF'
import plistlib, sys
p = plistlib.load(open(sys.argv[1], 'rb'))
for lib in p['AvailableLibraries']:
    print(f"{lib['LibraryIdentifier']} {lib['SupportedPlatform']} {lib.get('SupportedPlatformVariant','device')} {','.join(sorted(lib['SupportedArchitectures']))} {lib['LibraryPath']}")
EOF
)"
echo "$SLICES" | sed 's/^/   slice: /'
IFS=',' read -ra WANT <<< "$PLATFORMS"
for want in "${WANT[@]}"; do
  case "$want" in
    ios)          echo "$SLICES" | grep -qE '^[^ ]+ ios device arm64 '                 || die "missing ios device arm64 slice" ;;
    iossimulator) echo "$SLICES" | grep -qE '^[^ ]+ ios simulator .*arm64.* '          || die "missing ios simulator arm64 slice" ;;
    macos)        echo "$SLICES" | grep -qE '^[^ ]+ macos device .*arm64.* '           || die "missing macos arm64 slice" ;;
    tvos)         echo "$SLICES" | grep -qE '^[^ ]+ tvos device arm64 '                || die "missing tvos device slice" ;;
    tvossimulator)echo "$SLICES" | grep -qE '^[^ ]+ tvos simulator .*arm64.* '         || die "missing tvos simulator slice" ;;
    *) die "unknown platform '$want'" ;;
  esac
done
while read -r id _platform _variant archs libpath; do
  bin="$FW/$id/$libpath/Libbox"
  [ -f "$bin" ] || die "binary missing: $bin"
  [ -f "$FW/$id/$libpath/Headers/Libbox.h" ] || [ -f "$FW/$id/$libpath/Headers/Libbox.objc.h" ] || die "headers missing in $id"
  [ -f "$FW/$id/$libpath/Modules/module.modulemap" ] || die "module map missing in $id"
  lipo_archs="$(lipo -archs "$bin" | tr ' ' '\n' | sort | paste -sd, -)"
  [ "$lipo_archs" = "$archs" ] || die "$id: lipo reports [$lipo_archs] but Info.plist says [$archs]"
  echo "   $id: lipo archs = $lipo_archs OK"
done <<< "$SLICES"
grep -q "Libbox" "$FW"/*/Libbox.framework/Headers/*.h || die "public header does not export Libbox symbols"
BUILT_VERSION="$(strings "$FW"/ios-arm64/Libbox.framework/Libbox 2>/dev/null | grep -m1 -x "${TAG#v}" || true)"
[ -n "$BUILT_VERSION" ] && echo "   embedded constant.Version = $BUILT_VERSION" || echo "   (embedded version string not found in ios-arm64 binary — informational only)"

# ---------------------------------------------------- deterministic zip + hash
step "deterministic archive + SHA-256"
( cd "$OUT" && find Libbox.xcframework -exec touch -t 200001010000 {} + && \
  find Libbox.xcframework \( -type f -o -type l \) | LC_ALL=C sort | zip -X -q -y -@ Libbox.xcframework.zip )
SHA="$(shasum -a 256 "$OUT/Libbox.xcframework.zip" | cut -d' ' -f1)"
echo "$SHA  Libbox.xcframework.zip" > "$OUT/Libbox.xcframework.zip.sha256"
echo "   sha256 = $SHA"
if [ -n "${LIBBOX_EXPECTED_SHA256:-}" ]; then
  [ "$SHA" = "$LIBBOX_EXPECTED_SHA256" ] || die "SHA-256 mismatch: expected $LIBBOX_EXPECTED_SHA256"
  echo "   matches LIBBOX_EXPECTED_SHA256"
fi

# ----------------------------------------------------------------- provenance
GOMOBILE_BIN_VERSION="$(go version -m "$GOBIN_DIR/gomobile" | awk '$1=="mod"{print $3}')"
{
  echo "# Libbox.xcframework provenance"
  echo "sing_box_version: $TAG"
  echo "sing_box_source: https://github.com/SagerNet/sing-box (tag $TAG)"
  echo "sing_box_commit: $ACTUAL_COMMIT"
  echo "build_path: make lib_install && go run ./cmd/internal/build_libbox -target apple -platform $PLATFORMS"
  echo "platforms: $PLATFORMS"
  echo "go_version: $GO_VERSION_RAW"
  echo "gomobile_version: $GOMOBILE_VERSION (binary reports ${GOMOBILE_BIN_VERSION:-unknown})"
  echo "xcode_version: $XCODE_VERSION"
  echo "macos_version: $MACOS_VERSION"
  echo "build_timestamp_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "output: Libbox.xcframework.zip"
  echo "output_sha256: $SHA"
  echo "slices:"
  echo "$SLICES" | sed 's/^/  - /'
  echo "note: zip is deterministic (fixed mtimes, sorted entries) but Go/Xcode output is only reproducible for identical toolchains"
} > "$OUT/PROVENANCE.txt"
cat "$OUT/PROVENANCE.txt"
echo
echo ">> done: $OUT"
