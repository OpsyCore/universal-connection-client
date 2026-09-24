#!/usr/bin/env bash
# Builds libbox.aar from the pinned sing-box tag. Used by CI and by developers
# with Go + Android NDK installed.
#
#   SING_BOX_TAG   (default: value in core/engine-singbox/singbox.version)
#   ANDROID_NDK_HOME must point at NDK r28 (sing-box's build script insists on it)
#   JAVA_HOME must be a JDK 17 (sing-box's build script checks `openjdk 17`)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${SING_BOX_TAG:-$(cat "$ROOT/core/engine-singbox/singbox.version")}"
WORK="${LIBBOX_WORK:-$ROOT/build/sing-box-src}"
OUT="$ROOT/core/engine-singbox/libs"

echo ">> sing-box $TAG"
if [ ! -d "$WORK/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/SagerNet/sing-box.git "$WORK"
else
  git -C "$WORK" fetch --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
  git -C "$WORK" checkout -q "$TAG"
fi

pushd "$WORK" >/dev/null
make lib_install
# Only the main (minSdk 24) variant is needed; build_libbox also builds a legacy AAR, which we discard.
go run ./cmd/internal/build_libbox -target android
popd >/dev/null

mkdir -p "$OUT"
cp "$WORK/libbox.aar" "$OUT/libbox.aar"
sha256sum "$OUT/libbox.aar" | tee "$OUT/libbox.aar.sha256"
echo ">> built $OUT/libbox.aar"
