#!/usr/bin/env bash
# Release artifact inspection. Usage: tools/inspect-release.sh <apk> [aab]
# Prints package identity, permissions, ABIs and debug flags of the *built*
# artifacts and fails on release-only mistakes (debuggable, missing native
# libs, unexpected permissions, boot permission, unminified dex).
set -euo pipefail
APK=${1:?apk path}; AAB=${2:-}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
[ -x "$AAPT" ] || { echo "::error::aapt2 not found under $SDK/build-tools"; exit 1; }
fail=0; err() { echo "::error::$1"; fail=1; }

echo "=== APK: $APK ($(du -h "$APK" | cut -f1))"
BADGING=$("$AAPT" dump badging "$APK")
echo "$BADGING" | grep -E "^package:|^sdkVersion|^targetSdkVersion|^application-label:|^native-code"
PKG=$(echo "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
VNAME=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
VCODE=$(echo "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
[ "$PKG" = "io.ucc.app" ] || err "package is $PKG, expected io.ucc.app"
[ "$VNAME" = "1.0.0" ] || err "versionName is $VNAME, expected 1.0.0"
[ "$VCODE" = "1" ] || err "versionCode is $VCODE, expected 1"

echo "--- permissions"
echo "$BADGING" | grep "^uses-permission" | sed "s/uses-permission: name='\([^']*\)'.*/\1/" | sort | tee /tmp/perms.txt
EXPECTED="android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.QUERY_ALL_PACKAGES
android.permission.RECEIVE_BOOT_COMPLETED"
if ! diff <(echo "$EXPECTED") /tmp/perms.txt >/tmp/permdiff.txt; then cat /tmp/permdiff.txt; err "permission set differs from docs/RELEASE.md"; fi
# RECEIVE_BOOT_COMPLETED is merged from androidx.work (RescheduleReceiver re-enqueues periodic
# subscription refresh after reboot). The app declares none itself; verify that is still true.
grep -rq "RECEIVE_BOOT_COMPLETED" --include=AndroidManifest.xml --exclude-dir=build . 2>/dev/null && grep -rn "RECEIVE_BOOT_COMPLETED" --include=AndroidManifest.xml --exclude-dir=build . | grep -v "<!--\|^\s*No RECEIVE" | grep -q "uses-permission" && err "app declares RECEIVE_BOOT_COMPLETED itself" || echo "   RECEIVE_BOOT_COMPLETED: merged from androidx.work only"

echo "--- manifest flags"
MANIFEST=$("$AAPT" dump xmltree --file AndroidManifest.xml "$APK")
echo "$MANIFEST" | grep -q 'debuggable.*=true' && err "release APK is debuggable" || echo "   debuggable: false"
echo "$MANIFEST" | grep -q 'allowBackup.*=false' && echo "   allowBackup: false" || err "allowBackup is not false"
echo "$MANIFEST" | grep -q 'foregroundServiceType.*0x40000000' && echo "   VpnService foregroundServiceType=specialUse" || err "specialUse fgs type missing"
echo "$MANIFEST" | grep -q 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' && echo "   specialUse subtype property present" || err "specialUse subtype property missing"

echo "--- contents"
unzip -l "$APK" > /tmp/apklist.txt
echo "   dex files: $(grep -c 'classes[0-9]*\.dex' /tmp/apklist.txt)"
echo "   native libs:"; grep -E '^\s*[0-9]+.*lib/.*\.so$' /tmp/apklist.txt | awk '{print "     "$4" ("$1" bytes)"}'
for abi in arm64-v8a armeabi-v7a x86_64; do grep -q "lib/$abi/libbox.so\|lib/$abi/libgojni.so" /tmp/apklist.txt || err "libbox native library missing for $abi"; done
grep -E 'lib/x86/' /tmp/apklist.txt && err "unexpected x86 (32-bit) native lib" || true
grep -qE 'lib/.*/libgojni\.so|lib/.*/libbox\.so' /tmp/apklist.txt || err "no Go native library in APK"
echo "   debug-only markers:"
grep -E 'DebugProbesKt|kotlin/coroutines/jvm/internal/DebugProbes|META-INF/.*\.kotlin_module|okhttp3/internal/platform/android/.*Debug|ui-tooling' /tmp/apklist.txt || echo "     none"
grep -q 'META-INF/com/android/build/gradle/app-metadata.properties' /tmp/apklist.txt && unzip -p "$APK" META-INF/com/android/build/gradle/app-metadata.properties | sed 's/^/   /'

echo "--- licence assets"
grep -q 'assets/licenses/GPL-3.0.txt' /tmp/apklist.txt && echo "   assets/licenses/GPL-3.0.txt present" || err "GPL text asset missing from APK"

echo "--- minification evidence"
# An unminified build keeps every io.ucc class name; R8 obfuscates all but kept serializers/entry points.
unzip -o -q "$APK" 'classes*.dex' -d /tmp/apkdex
CLS=$(cat /tmp/apkdex/classes*.dex | strings | grep -c '^Lio/ucc/app/ui/[A-Za-z]*ScreenKt;' || true)
rm -rf /tmp/apkdex
if [ "$CLS" -gt 3 ]; then err "many io.ucc.app.ui.*ScreenKt names survive → dex looks unminified ($CLS)"; else echo "   io.ucc.app.ui.*ScreenKt names in dex: $CLS (obfuscated)"; fi

echo "--- signature"
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if "$APKSIGNER" verify --print-certs "$APK" >/tmp/sig.txt 2>&1; then echo "   SIGNED"; grep -E "Signer|SHA-256" /tmp/sig.txt | head -3; else echo "   UNSIGNED (no signing material in this environment)"; fi

if [ -n "$AAB" ] && [ -f "$AAB" ]; then
  echo; echo "=== AAB: $AAB ($(du -h "$AAB" | cut -f1))"
  unzip -l "$AAB" > /tmp/aablist.txt
  echo "   modules: $(awk '{print $4}' /tmp/aablist.txt | grep -oE '^[a-z_]+/' | sort -u | tr '\n' ' ')"
  echo "   native libs:"; grep -E 'base/lib/.*\.so$' /tmp/aablist.txt | awk '{print "     "$4}'
  for abi in arm64-v8a armeabi-v7a x86_64; do grep -q "base/lib/$abi/" /tmp/aablist.txt || err "AAB missing $abi"; done
  grep -q 'base/manifest/AndroidManifest.xml' /tmp/aablist.txt && echo "   manifest: base/manifest/AndroidManifest.xml" || err "AAB manifest missing"
  grep -q 'BundleConfig.pb' /tmp/aablist.txt && echo "   BundleConfig.pb present"
  echo "   dex: $(grep -c 'base/dex/classes' /tmp/aablist.txt)"
  # identity from the protobuf manifest (aapt2 cannot read it; grep the strings)
  unzip -p "$AAB" base/manifest/AndroidManifest.xml | strings | grep -E '^io\.ucc\.app$|^1\.0\.0$' | sort -u | sed 's/^/   id\/version string: /'
fi

[ $fail -eq 0 ] && echo "OK: artifact inspection passed" || { echo "artifact inspection FAILED"; exit 1; }
