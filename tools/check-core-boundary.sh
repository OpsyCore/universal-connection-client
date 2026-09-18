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
if grep -rn --include='*.kt' "import io.ucc.core.singbox" core/model core/engine-api core/config core/smart core/vpn app/src/main 2>/dev/null; then fail=1; else echo "   none"; fi

echo ">> Gradle: engine modules wired only where allowed"
if grep -n 'project(":core:engine-singbox")\|project(":core:singbox-config")' core/model/build.gradle.kts core/engine-api/build.gradle.kts core/config/build.gradle.kts core/smart/build.gradle.kts core/vpn/build.gradle.kts 2>/dev/null; then fail=1; else echo "   none"; fi
if grep -n '^\s*implementation(project(":core:engine-singbox"))\|^\s*implementation(project(":core:singbox-config"))' app/build.gradle.kts; then echo "   app must use a flavour-scoped configuration (e.g. singboxImplementation)"; fail=1; else echo "   app OK (flavour-scoped)"; fi

echo ">> KMP: commonMain/commonTest must not import platform APIs (android.*, androidx.*, java.*, javax.*, kotlin.jvm.*)"
if grep -rnE --include='*.kt' "^import (android|androidx|java|javax|kotlin\.jvm)\." core/*/src/commonMain core/*/src/commonTest 2>/dev/null; then fail=1; else echo "   none"; fi
echo ">> KMP: fully-qualified java./javax. references in commonMain"
if grep -rnE --include='*.kt' "\b(java|javax)\.[a-z]+\.[A-Z]" core/*/src/commonMain 2>/dev/null | grep -vE ":\s*(\*|//|/\*)"; then fail=1; else echo "   none"; fi
echo ">> KMP: platform actuals only in core/platform, core/config, core/smart (declared jvmMain/iosMain boundaries)"
if ls -d core/*/src/jvmMain core/*/src/iosMain 2>/dev/null | grep -vE "^core/(platform|config|smart)/src/(jvmMain|iosMain)$"; then echo "   unexpected platform source set (document it here if intentional)"; fail=1; else echo "   OK"; fi
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

if [ $fail -ne 0 ]; then echo "!! core boundary violated"; exit 1; fi
echo "OK: core boundary intact"
