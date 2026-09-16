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
if grep -rn --include='*.kt' "import io.ucc.core.singbox" core/model core/engine-api core/config core/vpn app/src/main 2>/dev/null; then fail=1; else echo "   none"; fi

echo ">> Gradle: engine modules wired only where allowed"
if grep -n 'project(":core:engine-singbox")\|project(":core:singbox-config")' core/model/build.gradle.kts core/engine-api/build.gradle.kts core/config/build.gradle.kts core/vpn/build.gradle.kts 2>/dev/null; then fail=1; else echo "   none"; fi
if grep -n '^\s*implementation(project(":core:engine-singbox"))\|^\s*implementation(project(":core:singbox-config"))' app/build.gradle.kts; then echo "   app must use a flavour-scoped configuration (e.g. singboxImplementation)"; fail=1; else echo "   app OK (flavour-scoped)"; fi

if [ $fail -ne 0 ]; then echo "!! core boundary violated"; exit 1; fi
echo "OK: core boundary intact"
