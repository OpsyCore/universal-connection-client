#!/usr/bin/env bash
# License/notice presence check for the release. Verifies that the files the
# in-app licence screen and the docs promise actually exist and agree on the
# core version, and that the notice inventory covers the shipped native core.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
fail=0; err() { echo "::error::$1"; fail=1; }

[ -s LICENSE ] && grep -q "GNU GENERAL PUBLIC LICENSE" LICENSE && grep -q "Version 3" LICENSE || err "LICENSE missing or not GPLv3"
[ -s THIRD_PARTY_NOTICES.md ] || err "THIRD_PARTY_NOTICES.md missing"
TAG=$(tr -d ' \n' < core/engine-singbox/singbox.version)
grep -q "$TAG" THIRD_PARTY_NOTICES.md || err "THIRD_PARTY_NOTICES.md does not mention sing-box $TAG"
grep -q "$TAG" docs/CORE_LICENSE_AUDIT.md || err "docs/CORE_LICENSE_AUDIT.md does not mention sing-box $TAG"
grep -q "PINNED_TAG = \"$TAG\"" core/singbox-config/src/commonMain/kotlin/io/ucc/core/singbox/SingBoxCapabilities.kt 2>/dev/null \
  || grep -rq "\"$TAG\"" core/singbox-config/src/commonMain/kotlin || err "SingBoxCapabilities pin does not match singbox.version ($TAG)"
[ -s app/src/main/assets/licenses/GPL-3.0.txt ] && cmp -s <(grep -v '^$' LICENSE | head -3) <(grep -v '^$' app/src/main/assets/licenses/GPL-3.0.txt | head -3) || err "bundled GPL text (assets/licenses/GPL-3.0.txt) missing or differs from LICENSE"
grep -qi "nekohasekai" THIRD_PARTY_NOTICES.md || err "sing-box copyright holder attribution missing"
grep -qi "ML Kit" THIRD_PARTY_NOTICES.md || err "ML Kit terms not listed"
for f in docs/PRIVACY_POLICY.md docs/DATA_SAFETY.md docs/PLAY_STORE_CHECKLIST.md docs/RELEASE.md; do [ -s "$f" ] || err "$f missing"; done
# In-app notices are core-agnostic: the engine line comes from CoreDescriptor at runtime. Check the descriptor names the core and pin.
grep -rq "sing-box" core/engine-singbox/src/main/kotlin core/singbox-config/src/commonMain/kotlin || err "core descriptor does not name sing-box"
grep -q "coreFactory.descriptor" app/src/main/kotlin/io/ucc/app/data/Notices.kt || err "in-app notices do not include the engine descriptor"
[ $fail -eq 0 ] && echo "OK: licence notices present (sing-box $TAG)" || exit 1
