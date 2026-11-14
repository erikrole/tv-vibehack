# Basement Home Theater

A custom build of tv-vibehack for one Hisense HiSmartTV A4 (Android TV 11).

## Install (one command)

```bash
TV_HOST=192.168.1.218 basement/install.sh
```

The target is required; the script never picks a device for you. It builds the launcher, installs it, disables
the apps in [`examples/hisense-hismarttv-a4/debloat.txt`](../examples/hisense-hismarttv-a4/debloat.txt), sets the
launcher as Home and disables the stock one, and applies the keep-the-network-up settings. Use `--no-debloat`
to skip the disable step.

## Restore

```bash
TV_HOST=192.168.1.218 basement/restore.sh
```

Re-enables everything, brings the stock launcher back and removes the custom one.

## Home Assistant (scenes and sleep timer)

Create a long-lived access token in your Home Assistant profile and add two lines to `launcher/local.properties`
(gitignored, never committed):

```
ha.url=http://homeassistant.local:8123
ha.token=YOUR_LONG_LIVED_TOKEN
```

Scene buttons and the TV entity are listed in `launcher/app/src/main/res/values/config.xml`
(`ha_scene_ids`, `ha_scene_labels`, `ha_tv_entity`). The sleep timer calls `media_player.turn_off` on that entity.

## Using it

- **Apps:** free-standing logos and wordmarks in four columns, with small labels and a short focus underline. Favorites from `dock_apps` come first, then `grid_apps`, then remaining apps alphabetically. `columns` controls the catalogue layout. Known apps use local vector marks; other installed icons are prepared off the main thread and capped at 256 pixels per side. No full app banners are decoded.
- **Remote:** Left/Right stay within the row; Up/Down move one row. Up from the first row reaches the header; Down from the final row reaches room scenes; Up from scenes returns to the selected app. Hold OK for Android's app options.
- **Room controls:** the sliders icon opens inputs, sleep timer, and notification modes in a side panel. Input names can scroll horizontally. On the Hisense the hardware Menu key opens the TV's own menu, so use the on-screen control.
- **Sports:** unboxed upcoming/live information for the teams in `sports_teams`. The cached schedule refreshes when loaded after five minutes near a live game, otherwise after three hours; the notification watcher also checks live scores when a game is near/live.
- **Room scenes:** text actions along the bottom. They use the configured Home Assistant scene IDs.

## Emulator preview and checks

Always disable Home Assistant for a preview so an emulator cannot publish playback state or activate scenes on the real house:

```bash
cd launcher
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew -PwithoutHomeAssistant=true assembleDebug assembleDebugAndroidTest lintDebug --console=plain
tests/run-device.sh emulator-5554
```

`tests/run-device.sh` requires an explicitly named emulator and refuses physical TVs. It builds eight synthetic apps with oversized artwork, then checks app navigation, cached metadata, package-update focus, Settings-to-Home return, long HDMI names, bounded 4K snapshot decoding, alert replacement timing, sticky priority, and queued-notification limits. Synthetic fixtures and private runtime data are generated outside the repository. The launcher uses no banner artwork even when the fixture APK contains it. Vector sources are listed in [`docs/brand-marks.md`](../docs/brand-marks.md).

The current redesign is installed only in the emulator. Physical-TV visual acceptance and actual Home Assistant/camera behavior remain separate checks. For a future UI-only update, build with the normal private configuration and install only the launcher APK over the exact TV's existing ADB connection; the full install script also changes system settings and package enablement.
