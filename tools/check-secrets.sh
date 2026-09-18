#!/usr/bin/env bash
# Repository secret scan. Fails when something that looks like a credential is
# tracked by git. Matches are printed only as file:line (never the content).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
fail=0
report() { echo "::error::$1"; fail=1; }

echo ">> tracked keystores / key material"
if git ls-files | grep -E '\.(jks|keystore|p12|pem|pfx|key)$|keystore\.properties$|^\.env' ; then report "key material tracked by git"; else echo "   none"; fi

echo ">> private key blocks"
if git grep -n -I -E -- '-----BEGIN (RSA |EC |OPENSSH |DSA |)PRIVATE KEY-----' -- ':!tools/check-secrets.sh' | cut -d: -f1,2 | grep . ; then report "private key block in tree"; else echo "   none"; fi

echo ">> credential-looking assignments (outside tests/docs)"
pat='(password|passwd|secret|api[_-]?key|access[_-]?token|bearer)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}'
if git grep -n -I -i -E -- "$pat" -- ':!*/test/*' ':!*/commonTest/*' ':!*/jvmTest/*' ':!*/androidTest/*' ':!docs/*' ':!*.md' ':!tools/check-secrets.sh' | cut -d: -f1,2 | grep . ; then report "credential-looking literal"; else echo "   none"; fi

echo ">> URLs with embedded credentials (outside tests/docs/parsers)"
if git grep -n -I -E -- '(https?|vless|vmess|trojan|ss|hysteria2?|tuic|socks5?)://[^/@[:space:]"]+:[^/@[:space:]"]+@[a-zA-Z0-9.-]+\.[a-z]{2,}' -- ':!*/test/*' ':!*/commonTest/*' ':!*/jvmTest/*' ':!docs/*' ':!*.md' ':!core/config/src/commonMain/*' ':!tools/check-secrets.sh' | cut -d: -f1,2 | grep . ; then report "URL with credentials"; else echo "   none"; fi

echo ">> well-known token shapes"
if git grep -n -I -E -- '(AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35})' -- ':!tools/check-secrets.sh' | cut -d: -f1,2 | grep . ; then report "known token shape"; else echo "   none"; fi

echo ">> signing config must not inline credentials"
if grep -n -E 'storePassword *= *"|keyPassword *= *"' app/build.gradle.kts ; then report "inline signing password"; else echo "   none"; fi

[ $fail -eq 0 ] && echo "OK: no secrets found" || exit 1
