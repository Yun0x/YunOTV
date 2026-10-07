#!/bin/zsh
set -euo pipefail
cd "${0:A:h:h}"
source scripts/project-env.sh
fixture_dir="${1:-/tmp/yunotv-runtime-fixtures}"
android_api_jar="$ANDROID_HOME/platforms/android-37.0/android.jar"
[[ -f "$android_api_jar" ]] || android_api_jar="$ANDROID_HOME/platforms/android-37/android.jar"
mkdir -p "$fixture_dir/classes" "$fixture_dir/dex"
"$JAVA_HOME/bin/javac" --release 8 -cp "${android_api_jar}:core-runtime/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes" -d "$fixture_dir/classes" tools/runtime-fixtures/com/github/catvod/spider/YunoFixture.java
"$JAVA_HOME/bin/jar" cf "$fixture_dir/classes.jar" -C "$fixture_dir/classes" .
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 26 --lib "$android_api_jar" --classpath core-runtime/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes --output "$fixture_dir/dex" "$fixture_dir/classes.jar"
"$JAVA_HOME/bin/jar" cf "$fixture_dir/fixture.jar" -C "$fixture_dir/dex" classes.dex
