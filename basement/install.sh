#!/usr/bin/env bash
# One command: build the launcher, install it on the TV, debloat, keep the network up.
# Needs an explicit target, never defaults to a device:
#   TV_HOST=192.168.1.218 basement/install.sh [--no-debloat]
# Undo everything with basement/restore.sh. Home Assistant (scenes, sleep timer): put ha.url and ha.token in
# launcher/local.properties before running (see basement/README.md).
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.218 $0}"
cd "$(dirname "$0")/.."
TARGET="$TV_HOST:5555"
LIST=examples/hisense-hismarttv-a4/debloat.txt
STOCK=com.google.android.tvlauncher
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
tv() { adb -s "$TARGET" shell "$@" </dev/null; }

adb connect "$TARGET" >/dev/null
adb -s "$TARGET" get-state >/dev/null || { echo "TV not reachable at $TARGET (turn it on, enable USB debugging)"; exit 1; }

echo "== build"
( cd launcher && { [ -f local.properties ] && grep -q '^sdk.dir=' local.properties || echo "sdk.dir=$ANDROID_HOME" >> local.properties; } && ./gradlew assembleDebug -q --console=plain )
echo "== install launcher"
adb -s "$TARGET" install -r launcher/app/build/outputs/apk/debug/app-debug.apk
tv pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION || true
echo "== playback reporting (notification access lets the launcher read media sessions)"
tv cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.PlaybackReporter || true

if [ "${1:-}" != "--no-debloat" ]; then
  echo "== disable packages from $LIST (reversible)"
  for p in $(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}'); do tv pm disable-user --user 0 "$p"; done
fi

echo "== keep the network up"
tv settings put global stay_on_while_plugged_in 7
tv settings put system screen_off_timeout 2147483647
tv settings put global app_standby_enabled 0

echo "== snappier system animations (restore.sh resets them to 1.0)"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do tv settings put global $k 0.5; done

echo "== make it home"
tv cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
tv pm disable-user --user 0 "$STOCK"
tv input keyevent KEYCODE_HOME
echo "Done. Undo with: TV_HOST=$TV_HOST basement/restore.sh"
