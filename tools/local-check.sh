#!/usr/bin/env bash
# Sandbox-tier verification for the pure-Kotlin modules.
#
# The authoritative build is `./gradlew assembleDebug testDebugUnitTest` (CI).
# This script exists for environments that cannot reach Maven Central / Google
# Maven (no AGP, no AndroidX). It compiles every JVM-only module with a
# standalone kotlinc and runs their unit tests with a minimal reflective
# runner that understands @kotlin.test.Test. Android modules are skipped.
#
# Requirements (set via env or auto-detected):
#   KOTLINC_HOME  - directory containing bin/kotlinc and lib/*.jar
#   JAVA_HOME     - JRE 17+
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:-/tmp/probe/kotlinc}"
JAVA_HOME="${JAVA_HOME:-/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
OUT="$ROOT/build/local-check"
LIB="$KOTLINC_HOME/lib"

# module id -> source dir ; dependency order matters
MODULES=(
  "core/model"
  "core/engine-api"
  "core/singbox-config"
  "core/config"
  "core/smart"
)

# Serialization plugin needed by modules using @Serializable
SER_PLUGIN="$LIB/kotlinx-serialization-compiler-plugin.jar"
BASE_CP="$LIB/kotlin-stdlib.jar:$LIB/kotlinx-coroutines-core-jvm.jar:$LIB/kotlin-reflect.jar"
SER_RUNTIME="${SER_RUNTIME:-/tmp/deps/kotlinx-serialization-core-jvm-1.9.0.jar:/tmp/deps/kotlinx-serialization-json-jvm-1.9.0.jar}"
TEST_CP="$LIB/kotlin-test.jar:$LIB/kotlin-test-junit.jar"

mkdir -p "$OUT/deps"
for j in ${SER_RUNTIME//:/ }; do
  [ -f "$j" ] || { echo "!! kotlinx-serialization runtime jar missing: $j (set SER_RUNTIME=core.jar:json.jar)"; exit 2; }
done

# Build the mini test runner once
if [ ! -f "$OUT/runner/io/ucc/tools/MiniRunnerKt.class" ]; then
  mkdir -p "$OUT/runner"
  "$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -cp "$BASE_CP" -d "$OUT/runner" \
      "$ROOT/tools/mini-runner/MiniRunner.kt" "$ROOT/tools/mini-runner/junit-stub" 2>&1 | grep -v "^warning: " || true
fi

fail=0
CP="$BASE_CP:$SER_RUNTIME"
for m in "${MODULES[@]}"; do
  src="$ROOT/$m/src/main/kotlin"
  [ -d "$src" ] || continue
  name="${m//\//_}"
  echo "== compile $m"
  rm -rf "$OUT/$name"
  mkdir -p "$OUT/$name/main" "$OUT/$name/test"
  "$KOTLINC_HOME/bin/kotlinc" -Xplugin="$SER_PLUGIN" -jvm-target 17 -Xexplicit-api=strict \
      -cp "$CP" -d "$OUT/$name/main" "$src" 2>&1 | grep -v "^warning: " || true
  [ -n "$(find "$OUT/$name/main" -name '*.class' | head -1)" ] || { echo "!! no classes produced for $m"; fail=1; continue; }
  CP="$CP:$OUT/$name/main"

  tsrc="$ROOT/$m/src/test/kotlin"
  if [ -d "$tsrc" ] && [ -n "$(find "$tsrc" -name '*.kt' | head -1)" ]; then
    echo "== compile tests $m"
    "$KOTLINC_HOME/bin/kotlinc" -Xplugin="$SER_PLUGIN" -jvm-target 17 \
        -cp "$CP:$TEST_CP:$OUT/runner" -d "$OUT/$name/test" "$tsrc" 2>&1 | grep -v "^warning: " || true
    echo "== run tests $m"
    java -cp "$CP:$TEST_CP:$OUT/$name/test:$OUT/runner" io.ucc.tools.MiniRunnerKt "$OUT/$name/test" || fail=1
  fi
done

exit $fail
