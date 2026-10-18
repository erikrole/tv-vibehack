#!/usr/bin/env bash
# Undo basement/install.sh: re-enable every disabled app and the stock launcher, remove the custom launcher
# and reset the settings. Needs an explicit target:  TV_HOST=192.168.1.218 basement/restore.sh
# (Apps that were uninstalled rather than disabled, like Speedtest, come back from the Play Store.)
set -uo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.218 $0}"
cd "$(dirname "$0")/.."
TARGET="$TV_HOST:5555"
LIST=examples/hisense-hismarttv-a4/debloat.txt
STOCK=com.google.android.tvlauncher
tv() { adb -s "$TARGET" shell "$@" </dev/null; }
adb connect "$TARGET" >/dev/null
for p in $(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}') "$STOCK"; do tv pm enable "$p"; done
tv cmd package set-home-activity --user 0 "$STOCK/com.google.android.tvlauncher.MainActivity"
tv pm uninstall com.gathin.tvhome
tv settings put global stay_on_while_plugged_in 3
tv settings put system screen_off_timeout 300000
tv settings put global app_standby_enabled 1
for k in window_animation_scale transition_animation_scale animator_duration_scale; do tv settings put global $k 1.0; done
tv input keyevent KEYCODE_HOME
echo "Restored. Disabled packages left:"; tv pm list packages -d
