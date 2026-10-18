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

- **Control Center:** the sliders pill in the top bar, or the remote's Menu key. Direct HDMI buttons and the
  sleep timer.
- **Badgers card:** next Wisconsin game from ESPN's public schedule, fetched once per launch and cached six hours.
  Which sports are checked: `badgers_schedules` in `config.xml`.
- **Dock:** `dock_apps` in `config.xml`.
