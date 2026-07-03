# Changelog

## 0.8.0 - 2026-07-03

- Make the LAN editor schedule-first: playlist items are edited inside schedule entries instead of a separate top-level playlist editor.
- Add an all-day playlist schedule option using `00:00` to `00:00`.
- Migrate legacy top-level `playlist` content into an all-day schedule in the LAN editor when no schedules exist.
- Save LAN-edited configs without a standalone top-level `playlist`.

## 0.7.0 - 2026-07-03

- Add media preview thumbnails to the LAN management page.
- Add `GET /media/preview` for downsampled image thumbnails and best-effort MP4 first-frame thumbnails.
- Let each playlist schedule edit its own playlist directly instead of only copying the top-level playlist.
- Add a selected schedule target so media library items can be added directly to a schedule playlist.
- Add per-schedule playlist reorder, remove, type, duration, and fit-mode controls.

## 0.6.0 - 2026-07-03

- Add LAN schedule editing for playlist and silent schedule entries.
- Add media rename and delete actions to the LAN media library.
- Prevent deleting media files that are still referenced by `config.json`.
- Update config media references automatically when a media file is renamed.
- Add rollback UI and `POST /config/rollback` for restoring `config.json.bak`.
- Add default-on LAN access protection with a tablet-shown access code and an optional disable/enable control.
- Improve LAN management page structure and error feedback for config, media, and access actions.
- Record thumbnail feasibility: image thumbnails are suitable for a later downsampled endpoint; video thumbnails should remain optional because old tablets may fail or stall during codec probing.

## 0.5.0 - 2026-07-03

- Add a graphical LAN playlist editor to the management page.
- Add media-library-to-playlist actions, reorder controls, remove controls, image duration editing, and fit mode editing.
- Add `POST /config` to validate and save `config.json` from the LAN page.
- Preserve existing settings and schedules while updating the top-level playlist.
- Back up the previous config to `config.json.bak` before replacing it.
- Show video items as play-to-end in the GUI instead of exposing an unused duration field.

## 0.4.0 - 2026-07-03

- Add a default-off LAN management server on port 8080.
- Add maintenance controls to start and stop the LAN server.
- Add browser pages for status, logs, current config, media list, and media upload.
- Save uploaded JPG, PNG, and MP4 files into `/sdcard/SimpleKiosk/media/`.
- Add `INTERNET` permission for the local HTTP server.

## 0.3.0 - 2026-07-03

- Add a local maintenance view opened by tapping the top-left corner 5 times within 10 seconds.
- Show app version, config path, device Wi-Fi IP, active mode, active schedule, playlist state, and recent logs.
- Add maintenance actions to reload config, refresh status, and resume playback.
- Add `ACCESS_WIFI_STATE` only for displaying the local device IP address.

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








