#!/usr/bin/env bash
# These checks open Settings and show synthetic overlays. Refuse physical TVs.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?usage: tests/run-device.sh emulator-5554}"
[[ "$serial" == emulator-* ]] || { echo "Use an emulator serial; this test must not run on a physical TV."; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
[[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "Device is not an emulator."; exit 1; }
fixtures=$(mktemp -d)
trap 'rm -rf "$fixtures"' EXIT
python3 tests/create-fixtures.py "$ANDROID_HOME" "$fixtures"
adb -s "$serial" install -r "$fixtures/fixture.apk"
./gradlew -PwithoutHomeAssistant=true assembleDebug assembleDebugAndroidTest --console=plain
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell am force-stop com.gathin.tvhome
result=$(adb -s "$serial" shell am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.LauncherChecks)
printf '%s\n' "$result"
[[ "$result" == *"result=PASS:"* && "$result" == *"INSTRUMENTATION_CODE: -1"* ]]
