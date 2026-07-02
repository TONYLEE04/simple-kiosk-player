# Changelog

## 0.2.1 - 2026-07-03

- Add silent schedules with `mode: "silent"`.
- Add `screen: "black"` and `screen: "allowSleep"` schedule behavior.
- Stop playback during silent schedules and allow Android to turn the screen off.
- Schedule playback resume with `AlarmManager.RTC_WAKEUP`.
- Wake the device for playback with a short wake lock and show over the non-secure keyguard.
- Add `samples/silent-scheduled-config.json`.

## 0.2.0 - 2026-07-03

- Add scheduled playback with `schedules`.
- Support `HH:mm` time ranges, including ranges that cross midnight.
- Add config hot reload while keeping the previous valid config running on parse errors.
- Add a scheduled config sample and note future local network settings direction.

## 0.1.0 - 2026-07-03

- Add initial Java Android kiosk player.
- Read config from `/sdcard/SimpleKiosk/config.json`.
- Play local image and video files from `/sdcard/SimpleKiosk/media/`.
- Loop playlists with image durations and video completion.
- Run fullscreen with black background, hidden system UI, and keep-screen-on playback.
- Show fullscreen errors and write logs to `/sdcard/SimpleKiosk/logs/player.log`.
