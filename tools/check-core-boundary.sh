#!/usr/bin/env bash
# Architecture guard: only engine modules may reference an engine SDK or an
# engine package. Run locally and in CI. Exit 1 on violation.
#
#   allowed to import libbox / io.ucc.core.singbox.*:  core/engine-singbox, core/singbox-config (own package)
#   allowed to import io.ucc.core.singbox.android.*Factory: app/src/<flavour>/ (flavour source sets only)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail=0

echo ">> libbox symbols outside core/engine-singbox"
if grep -rn --include='*.kt' --include='*.java' "io.nekohasekai.libbox\|^import go\." --exclude-dir=engine-singbox app core tools 2>/dev/null | grep -v "^core/engine-singbox/"; then fail=1; else echo "   none"; fi

echo ">> engine packages (io.ucc.core.singbox.*) imported from core-agnostic modules"
if grep -rn --include='*.kt' "import io.ucc.core.singbox" core/model core/engine-api core/config core/smart core/vpn core/ios-infra core/ios-vpn core/app-logic app/src/main 2>/dev/null; then fail=1; else echo "   none"; fi
if grep -n 'engine-singbox-apple' core/*/build.gradle.kts app/build.gradle.kts | grep -v "^core/engine-singbox-apple/"; then echo "   engine-singbox-apple must not be a dependency of any module yet (wired by the Xcode target only)"; fail=1; else echo "   engine-singbox-apple not depended upon"; fi

echo ">> Gradle: engine modules wired only where allowed"
if grep -n 'project(":core:engine-singbox")\|project(":core:singbox-config")' core/model/build.gradle.kts core/engine-api/build.gradle.kts core/config/build.gradle.kts core/smart/build.gradle.kts core/vpn/build.gradle.kts 2>/dev/null; then fail=1; else echo "   none"; fi
if grep -n '^\s*implementation(project(":core:engine-singbox"))\|^\s*implementation(project(":core:singbox-config"))' app/build.gradle.kts; then echo "   app must use a flavour-scoped configuration (e.g. singboxImplementation)"; fail=1; else echo "   app OK (flavour-scoped)"; fi

echo ">> KMP: commonMain/commonTest must not import platform APIs (android.*, androidx.*, java.*, javax.*, kotlin.jvm.*)"
if grep -rnE --include='*.kt' "^import (android|androidx|java|javax|kotlin\.jvm)\." core/*/src/commonMain core/*/src/commonTest 2>/dev/null; then fail=1; else echo "   none"; fi
echo ">> KMP: fully-qualified java./javax. references in commonMain"
if grep -rnE --include='*.kt' "\b(java|javax)\.[a-z]+\.[A-Z]" core/*/src/commonMain 2>/dev/null | grep -vE ":\s*(\*|//|/\*)"; then fail=1; else echo "   none"; fi
echo ">> KMP: platform actuals only in core/platform, core/config, core/smart; iosMain additionally in core/ios-infra (declared jvmMain/iosMain boundaries)"
if ls -d core/*/src/jvmMain core/*/src/iosMain 2>/dev/null | grep -vE "^core/(platform|config|smart)/src/(jvmMain|iosMain)$|^core/(ios-infra|ios-vpn|engine-singbox-apple)/src/iosMain$"; then echo "   unexpected platform source set (document it here if intentional)"; fail=1; else echo "   OK"; fi
echo ">> KMP: Apple APIs (platform.*, kotlinx.cinterop.*) only in iosMain/iosTest"
if grep -rnE --include='*.kt' "^import (platform|kotlinx\.cinterop)\." core/*/src/commonMain core/*/src/commonTest core/*/src/jvmMain core/*/src/jvmTest 2>/dev/null; then fail=1; else echo "   none"; fi
echo ">> KMP: JVM APIs (java.*, javax.*, kotlin.jvm.*) never in iosMain/iosTest"
if grep -rnE --include='*.kt' "^import (java|javax|kotlin\.jvm|android|androidx)\." core/*/src/iosMain core/*/src/iosTest 2>/dev/null; then fail=1; else echo "   none"; fi
echo ">> KMP: no Swift/Obj-C sources inside core modules"
if find core -type f \( -name '*.swift' -o -name '*.m' -o -name '*.mm' -o -name '*.h' -o -name '*.def' \) -not -path '*/build/*' | grep .; then fail=1; else echo "   none"; fi
echo ">> KMP: every expect in commonMain has a jvmMain and an iosMain actual"
for f in $(grep -rlE --include='*.kt' "^(public |internal )?expect fun" core/*/src/commonMain); do
  mod=$(echo "$f" | cut -d/ -f1-2)
  for name in $(grep -oE "expect fun [a-zA-Z0-9_]+" "$f" | awk '{print $3}'); do
    for ss in jvmMain iosMain; do
      grep -rqE "actual fun $name\b" "$mod/src/$ss" 2>/dev/null || { echo "   missing $ss actual for $name ($mod)"; fail=1; }
    done
  done
done
echo "   checked"

echo ">> KMP: core/app-logic must not depend on UI toolkits, Android, or engine modules"
if grep -rnE --include='*.kt' "^import (androidx\.compose|androidx\.lifecycle|android|androidx|java|javax|kotlin\.jvm|io\.ucc\.core\.singbox|io\.ucc\.core\.vpn|io\.ucc\.app)\." core/app-logic/src 2>/dev/null; then fail=1; else echo "   none"; fi
if grep -nE 'compose|lifecycle|androidx|engine-singbox|singbox-config|core:vpn' core/app-logic/build.gradle.kts | grep -v "^\s*//" | grep -v "^[0-9]*:\s*\*"; then echo "   forbidden dependency in core/app-logic/build.gradle.kts"; fail=1; else echo "   build script OK"; fi
echo ">> KMP: core/ios-infra commonMain is pure Kotlin (no Apple, JVM, Android, UI, engine, or app imports); no VPN/Libbox/NetworkExtension code"
if grep -rnE --include='*.kt' "^import (platform|kotlinx\\.cinterop|android|androidx|java|javax|kotlin\\.jvm|io\\.ucc\\.core\\.singbox|io\\.ucc\\.core\\.vpn|io\\.ucc\\.app)\\." core/ios-infra/src/commonMain core/ios-infra/src/commonTest 2>/dev/null; then fail=1; else echo "   commonMain/commonTest OK"; fi
if grep -rnE --include='*.kt' "^import (android|androidx|io\\.ucc\\.core\\.singbox|io\\.ucc\\.core\\.vpn|io\\.ucc\\.app)\\.|platform\\.NetworkExtension|Libbox" core/ios-infra/src 2>/dev/null; then fail=1; else echo "   no VPN/Libbox/NetworkExtension/app leakage"; fi
if grep -nE 'compose|lifecycle|androidx|engine-singbox|singbox-config|core:vpn|:app"' core/ios-infra/build.gradle.kts | grep -v "^[0-9]*:\s*\*" | grep -v "^[0-9]*:\s*//"; then echo "   forbidden dependency in core/ios-infra/build.gradle.kts"; fail=1; else echo "   build script OK"; fi
echo ">> Apple: NetworkExtension only in core/ios-vpn iosMain; Libbox linkage only in core/engine-singbox-apple iosMain"
if grep -rnE --include='*.kt' --include='*.def' "^import platform\\.NetworkExtension|: NEPacketTunnelProvider\\(" app core tools 2>/dev/null | grep -vE "^core/ios-vpn/src/iosMain/"; then fail=1; else echo "   NetworkExtension confined to core/ios-vpn iosMain"; fi
if grep -rnE --include='*.kt' --include='*.def' "^import (cocoapods\\.Libbox|Libbox)\\." app core 2>/dev/null | grep -v "^core/engine-singbox-apple/src/iosMain/"; then fail=1; else echo "   Libbox cinterop imports only in core/engine-singbox-apple iosMain"; fi
if grep -rnE --include='*.kt' "^import (cocoapods\\.Libbox|Libbox)\\." core/engine-singbox-apple/src/iosMain 2>/dev/null | grep .; then echo "   NOTE: Libbox linked — libbox-apple.sha256 must be pinned"; grep -qE '^[0-9a-f]{64}$' core/engine-singbox/libbox-apple.sha256 || fail=1; else echo "   no Libbox linked (expected while libbox-apple.sha256 = unpinned)"; fi
if grep -rn --include='*.kt' "io.nekohasekai.libbox" core/ios-infra core/ios-vpn core/engine-singbox-apple 2>/dev/null; then echo "   Android libbox classes in iOS modules"; fail=1; else echo "   no Android libbox in iOS modules"; fi
if find core/engine-singbox-apple core/ios-vpn ios -type f \( -name '*.xcframework' -o -name '*.a' -o -name '*.dylib' -o -name '*.framework' -o -name '*.zip' \) 2>/dev/null | grep .; then echo "   binary framework substitutes committed"; fail=1; else echo "   no framework binaries"; fi
echo ">> KMP: core/ios-vpn commonMain is pure Kotlin; no UI/app/engine imports anywhere in the module"
if grep -rnE --include='*.kt' "^import (platform|kotlinx\\.cinterop|android|androidx|java|javax|kotlin\\.jvm|io\\.ucc\\.core\\.singbox|io\\.ucc\\.core\\.vpn|io\\.ucc\\.app)\\." core/ios-vpn/src/commonMain core/ios-vpn/src/commonTest 2>/dev/null; then fail=1; else echo "   commonMain/commonTest OK"; fi
if grep -rnE --include='*.kt' "^import (android|androidx|io\\.ucc\\.core\\.singbox|io\\.ucc\\.core\\.vpn|io\\.ucc\\.app)\\." core/ios-vpn/src 2>/dev/null; then fail=1; else echo "   no Android/app/engine leakage"; fi
if grep -rnE --include='*.kt' "^import (android|androidx|io\\.ucc\\.core\\.singbox\\.android|io\\.ucc\\.core\\.vpn|io\\.ucc\\.app)\\." core/engine-singbox-apple/src 2>/dev/null; then fail=1; else echo "   engine-singbox-apple: no Android/app leakage"; fi
if grep -rnE --include='*.kt' "^import (platform|kotlinx\\.cinterop|cocoapods)\\." core/engine-singbox-apple/src/commonMain core/engine-singbox-apple/src/commonTest 2>/dev/null; then fail=1; else echo "   engine-singbox-apple commonMain framework-free"; fi
if grep -nE 'compose|lifecycle|androidx|engine-singbox|core:vpn|:app"' core/ios-vpn/build.gradle.kts | grep -v "^[0-9]*:\s*\*" | grep -v "^[0-9]*:\s*//"; then echo "   forbidden dependency in core/ios-vpn/build.gradle.kts"; fail=1; else echo "   build script OK"; fi
echo ">> Apple libbox pins: singbox.version, singbox.commit, libbox-apple.sha256 present and well-formed"
[ -s core/engine-singbox/singbox.version ] && grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$' core/engine-singbox/singbox.version || { echo "   singbox.version malformed"; fail=1; }
grep -qE '^[0-9a-f]{40}$' core/engine-singbox/singbox.commit || { echo "   singbox.commit must be a 40-hex commit"; fail=1; }
grep -qE '^([0-9a-f]{64}|unpinned)$' core/engine-singbox/libbox-apple.sha256 || { echo "   libbox-apple.sha256 must be 64-hex or 'unpinned'"; fail=1; }
grep -qE '^([0-9a-f]{64}|unpinned)$' core/engine-singbox/libbox.sha256 || { echo "   libbox.sha256 must be 64-hex or 'unpinned'"; fail=1; }
echo "   pins OK"
echo ">> app: shared application logic lives in core/app-logic, not app/data (only platform adapters may remain)"
if grep -rlE --include='*.kt' "^(class|object|interface) (ImportRepository|ServerRepository|SubscriptionRefresher|SmartConnectionCoordinator|LogBuffer|LogSanitizer|ConnectionSettings|ProfileStore|SubscriptionStore|SelectionStore|SettingsStore)\b" app/src/main 2>/dev/null; then echo "   duplicate of a shared type in app"; fail=1; else echo "   OK"; fi

if [ $fail -ne 0 ]; then echo "!! core boundary violated"; exit 1; fi
echo "OK: core boundary intact"
