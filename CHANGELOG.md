# Changelog

## 0.11.0 - 2026-07-04

- Add optional `management.autoStart` so sealed devices can start the LAN management server on app launch.
- Add optional local fixed admin password stored in `config.json`, while keeping the existing temporary code flow for default installs.
- Add LAN Device control actions for apply config now, black screen now, allow sleep now, and resume schedule.
- Make LAN schedule saves call apply-config automatically so touchless devices apply schedule changes immediately.
- Keep manual override state runtime-only and higher priority than schedules until Resume schedule is used.
- Add browser-side strict `HH:mm` validation for schedule start/end fields with clearer guidance.

## 0.10.2 - 2026-07-04

- Prevent very long media file names and paths from stretching the LAN media table layout.
- Add fixed table columns and internal table scrolling so other controls stay usable.

## 0.10.1 - 2026-07-04

- Replace the media library folder dropdown with a file-manager-style folder sidebar and breadcrumb navigation.
- Make the upload target follow the current folder when the target folder input is left empty.
- Add a direct "Upload here" action and rename folder batch insertion to "Add current folder".

## 0.10.0 - 2026-07-04

- Upgrade the LAN media library to a table with search, folder/type/risk filters, pagination, and batch add/delete actions.
- Support recursive media folders under `/sdcard/SimpleKiosk/media/`, including schedule entries such as `media/A-role/clip.mp4`.
- Add folder-as-group actions so all matching files in a selected folder can be added to the target schedule.
- Change `GET /media` to JSON with relative path, folder, type, size, metadata, reference state, and compatibility warnings.
- Add best-effort media compatibility analysis for dimensions, duration, fps, codec, H.264 level, HEVC/H.265, AV1, 4K-class, and 60fps risk.
- Skip videos that prepare with `0x0` dimensions instead of leaving the previous frame on screen.

## 0.9.0 - 2026-07-04

- Add a first-run setup screen for missing `config.json` that points users to the hidden maintenance gesture.
- Keep config reload polling active after startup config failures so LAN-created configs can load without restarting.
- Improve scheduled playback resilience by restarting inactive playlists and skipping bad media items instead of stopping the whole loop.
- Add explicit bitmap cleanup and out-of-memory handling for image playback.
- Add log rotation for `/sdcard/SimpleKiosk/logs/player.log` at 1 MB with one backup file.
- Add LAN playlist presets saved in `config.json` as reusable copy-to-schedule templates.
- Add queued multi-file upload from the LAN management page.
- Document newer-device smoke tests and limitations around sleep/wake and fullscreen behavior.

## 0.8.1 - 2026-07-03

- Add release APK signing configuration and a local keystore properties template.

## 0.8.0 - 2026-07-03

- Make the LAN editor schedule-first: playlist items are edited inside schedule entries instead of a separate top-level playlist editor.
- Add an all-day playlist schedule option using `00:00` to `00:00`.
- Migrate legacy top-level `playlist` content into an all-day schedule in the LAN editor when no schedules exist.
- Save LAN-edited configs without a standalone top-level `playlist`.
- Add the GPL v3-or-later project license.
- Add the optional official Keep Android Open banner to the LAN management page.

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








