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
# Permissions the app and its libraries are known to contribute (docs/RELEASE.md §Permissions).
# Anything outside this allow-list fails the inspection; anything missing from the
# app-owned set fails too.
ALLOWED="android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.QUERY_ALL_PACKAGES
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
com.google.android.gms.permission.AD_ID
${PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
REQUIRED="android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.QUERY_ALL_PACKAGES"
UNEXPECTED=$(comm -13 <(echo "$ALLOWED" | sort) /tmp/perms.txt || true)
MISSING=$(comm -23 <(echo "$REQUIRED" | sort) /tmp/perms.txt || true)
[ -z "$UNEXPECTED" ] || { echo "   unexpected:"; echo "$UNEXPECTED" | sed 's/^/     /'; err "permission(s) not in the documented allow-list"; }
[ -z "$MISSING" ] || { echo "   missing:"; echo "$MISSING" | sed 's/^/     /'; err "required permission(s) missing"; }
grep -q "AD_ID" /tmp/perms.txt && err "AD_ID present: the app must not carry the advertising-ID permission (add tools:node=remove)" || true
# RECEIVE_BOOT_COMPLETED policy: the app must not declare it. It may be present ONLY if
#  (a) the manifest-merger blame report attributes every occurrence to androidx.work
#      (RescheduleReceiver re-arms the app's periodic subscription refresh after reboot;
#      WorkManager schedules its JobScheduler jobs with setPersisted(false) on purpose), and
#  (b) docs/RELEASE.md carries the explicit approval marker below.
# Anything else (present but unattributed, or present without approval) fails the build.
BOOT_PERM="android.permission.RECEIVE_BOOT_COMPLETED"
APPROVAL_MARKER="APPROVED-PERMISSION: ${BOOT_PERM} (androidx.work RescheduleReceiver)"
if grep -rn "$BOOT_PERM" --include=AndroidManifest.xml --exclude-dir=build . 2>/dev/null | grep -v "tools:node=\"remove\"" | grep -q "<uses-permission"; then
  err "an app module declares ${BOOT_PERM} itself"
fi
if grep -qx "$BOOT_PERM" /tmp/perms.txt; then
  echo "   RECEIVE_BOOT_COMPLETED: PRESENT"
  BLAME=${MERGER_BLAME:-$(ls app/build/intermediates/manifest_merge_blame_file/*elease*/*/manifest-merger-blame-*-report.txt 2>/dev/null | head -1)}
  if [ -n "$BLAME" ] && [ -f "$BLAME" ]; then
    # Blame lines look like: "<path>:<line>:<col>" and follow the element they attribute; take the
    # attribution lines that immediately follow the uses-permission element.
    # Blame format: '<n>    <uses-permission .../>' followed by '<n>-->[group:artifact:ver] path:line'
    ATTR=$(grep -A1 "<uses-permission android:name=\"$BOOT_PERM\"" "$BLAME" | grep -oE '\-\->\[[^]]+\]|\-\->[^ ]*AndroidManifest\.xml' | sed 's/^-->//' | sort -u || true)
    echo "   merger blame for ${BOOT_PERM}:"; echo "${ATTR:-<none found>}" | sed 's/^/     /'
    if [ -z "$ATTR" ]; then err "${BOOT_PERM} present but the merger blame report does not attribute it"
    elif echo "$ATTR" | grep -v "work-runtime\|androidx.work" | grep -q .; then err "${BOOT_PERM} is contributed by something other than androidx.work"
    else echo "   attribution: androidx.work only"; fi
  else
    err "${BOOT_PERM} present but no manifest-merger blame report available to attribute it (set MERGER_BLAME)"
  fi
  grep -qF "$APPROVAL_MARKER" docs/RELEASE.md && echo "   approval: documented in docs/RELEASE.md" || err "${BOOT_PERM} present without the approval marker in docs/RELEASE.md"
else
  echo "   RECEIVE_BOOT_COMPLETED: ABSENT"
fi

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
  # The AAB manifest is protobuf; use bundletool for an exact, decoded read (strings on the binary is
  # unreliable). BUNDLETOOL_JAR is provided by CI; without it the AAB manifest check is skipped, not faked.
  if [ -n "${BUNDLETOOL_JAR:-}" ] && [ -f "$BUNDLETOOL_JAR" ]; then
    java -jar "$BUNDLETOOL_JAR" dump manifest --bundle "$AAB" > /tmp/aab-manifest.xml
    AABPKG=$(grep -oE 'package="[^"]+"' /tmp/aab-manifest.xml | head -1 | cut -d'"' -f2)
    AABVN=$(grep -oE 'android:versionName="[^"]+"' /tmp/aab-manifest.xml | head -1 | cut -d'"' -f2)
    AABVC=$(grep -oE 'android:versionCode="[^"]+"' /tmp/aab-manifest.xml | head -1 | cut -d'"' -f2)
    echo "   package=$AABPKG versionName=$AABVN versionCode=$AABVC"
    [ "$AABPKG" = "io.ucc.app" ] || err "AAB package is $AABPKG"
    [ "$AABVN" = "1.0.0" ] || err "AAB versionName is $AABVN"
    grep -q 'android:debuggable="true"' /tmp/aab-manifest.xml && err "AAB is debuggable" || echo "   debuggable: false"
    echo "   AAB permissions (bundletool dump manifest):"
    grep -oE '<uses-permission android:name="[^"]+"' /tmp/aab-manifest.xml | cut -d'"' -f2 | sort | tee /tmp/aabperms.txt | sed 's/^/     /'
    diff -q /tmp/perms.txt /tmp/aabperms.txt >/dev/null && echo "   AAB permission set == APK permission set" || err "AAB permission set differs from APK"
    if grep -qx "$BOOT_PERM" /tmp/aabperms.txt; then echo "   AAB RECEIVE_BOOT_COMPLETED: PRESENT (same attribution/approval as APK)"; else echo "   AAB RECEIVE_BOOT_COMPLETED: ABSENT"; fi
  else
    echo "   AAB manifest: NOT INSPECTED (BUNDLETOOL_JAR not set)"
  fi
fi

[ $fail -eq 0 ] && echo "OK: artifact inspection passed" || { echo "artifact inspection FAILED"; exit 1; }
