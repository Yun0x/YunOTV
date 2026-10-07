#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export YUNO_RUN_REMOTE_INTEGRATION=1
./gradlew :core-config:testDebugUnitTest --tests '*SourceBundleIntegrationTest'
# Optional emulator/device metadata integration (external service availability may change):
# ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yuno.tv.RealSourceIntegrationTest -Pandroid.testInstrumentationRunnerArguments.remoteIntegration=true
