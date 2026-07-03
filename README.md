# Simple Kiosk Player

Lightweight offline Android kiosk media player for old tablets.

## Local Development

This project is a Java-only Android app using native Android Views.

Current local SDK path:

```properties
sdk.dir=D\:\\Apps Catalog\\Android sdks
```

The app targets old Android tablets first:

- `minSdk 19`
- `targetSdk 19`
- local files only
- no AndroidX
- no Kotlin
- no Compose
- no Google Play Services

## License

Simple Kiosk Player is licensed under the GNU General Public License v3.0 or later (`GPL-3.0-or-later`). See `LICENSE`.

## Tested Android Versions

- Samsung SM-T331C, Android 4.4.2 / API 19: smoke-tested playback, scheduling, sleep/wake behavior, media upload, media previews, and the LAN management editor during the v0.8.0 development cycle.

## Device Storage Layout

Create this directory on the Android device:

```text
/sdcard/SimpleKiosk/
  config.json
  media/
  logs/
```

Use `samples/config.json` as the starting config.

## Build

Open this folder in Android Studio and let it sync Gradle.

If Android Studio asks to download Gradle or Android Gradle Plugin, allow it. The project currently uses Android Gradle Plugin `9.2.0`.

## Install With ADB

After the APK is built and the device is connected with USB debugging enabled:

```powershell
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" devices
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

## Copy Test Files

```powershell
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" shell mkdir -p /sdcard/SimpleKiosk/media
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" push samples/config.json /sdcard/SimpleKiosk/config.json
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" push intro.png /sdcard/SimpleKiosk/media/intro.png
& "D:\Apps Catalog\Android sdks\platform-tools\adb.exe" push demo.mp4 /sdcard/SimpleKiosk/media/demo.mp4
```

## Scheduling

The runtime still accepts legacy top-level `playlist` configs, but the LAN editor is schedule-first: playlist items are edited inside `schedules`.

Use `samples/scheduled-config.json` as the starting point for scheduled playback. Time values use `HH:mm` in the device local time. Cross-midnight ranges are supported, for example `22:30` to `08:00`. A playlist schedule with `start: "00:00"` and `end: "00:00"` is treated as all-day playback.

If `config.json` changes while the app is running, the app checks the file periodically and reloads a valid config automatically. Invalid hot-reloaded config is logged and ignored so the current playlist can continue running.


## Local Maintenance View

During playback, tap the top-left corner 5 times within 10 seconds to open the local maintenance view.

The maintenance view currently shows:

- App version
- `/sdcard/SimpleKiosk/` paths
- Config last modified time
- Device Wi-Fi IP address
- Current playback or silent mode
- Active schedule and playlist state
- Recent `logs/player.log` lines

Available actions:

- `Reload config`: reload `/sdcard/SimpleKiosk/config.json` immediately.
- `Refresh`: refresh the displayed status and logs.
- `Resume`: return to fullscreen playback.

This is intentionally local-only. It prepares the same status and config flow that a future LAN-only settings page can reuse.

## LAN Media Upload

The maintenance view can start a temporary LAN management page.

1. Open the local maintenance view from the tablet.
2. Tap `Start LAN`.
3. Open the shown URL from a phone or computer on the same Wi-Fi network, for example `http://192.168.1.23:8080/`.
4. Upload JPG, PNG, or MP4 files from the browser.

Uploaded files are saved to:

```text
/sdcard/SimpleKiosk/media/
```

The LAN service is off by default and stops when the app process exits. It is for trusted local networks only.

## LAN Management Editor

The LAN management page includes a graphical editor for common maintenance tasks.

Current editor scope:

- Shows files from `/sdcard/SimpleKiosk/media/` with thumbnail previews.
- Uploads JPG, PNG, and MP4 files.
- Renames media files and updates matching `media/...` references in `config.json`.
- Deletes unreferenced media files. Referenced files are rejected instead of silently breaking playback.
- Adds all-day playlist, timed playlist, or silent schedule entries.
- Edits each playlist schedule's own playlist directly, including reorder, remove, type, image duration, and fit mode.
- Migrates a legacy top-level `playlist` into an all-day `00:00` to `00:00` schedule when opened in the LAN editor.
- Saves LAN-edited configs without a standalone top-level `playlist`.
- Saves the updated JSON back to `/sdcard/SimpleKiosk/config.json`.
- Rolls back to `/sdcard/SimpleKiosk/config.json.bak` if the previous save should be restored.

Videos always play to completion. The duration field is shown only for image items.

After saving, the normal config hot reload path applies the new schedules automatically if the config is valid.

LAN access protection is on by default while the management server is running. The tablet maintenance view shows a URL with a one-time access code for the current app process. The web page can temporarily disable or re-enable this protection for trusted local networks.

Thumbnail previews are served locally by the app. Image files are downsampled before being sent to the browser. MP4 files use a best-effort first-frame preview and fall back to a lightweight placeholder if the old device cannot extract a frame.

The LAN management page embeds the official Keep Android Open banner from `keepandroidopen.org` when the browser can reach the public internet. Playback, upload, editing, and local management continue to work if that external script cannot load.

## Future Local Network Setup

The runtime source of truth is still `/sdcard/SimpleKiosk/config.json`.

A future LAN-only maintenance page should write the same config file and media directory instead of introducing a separate cloud or account system. This keeps playback offline-first and lets the existing hot reload path apply changes without restarting the app.

## Roadmap

This project will remain offline-first and old-device-friendly. Future work should preserve compatibility with current `/sdcard/SimpleKiosk/` configs and media layouts whenever possible.

Planned directions:

- Compatibility updates for newer Android versions while preserving Android 4.4 / API 19 support where practical.
- Import and migration helpers for older config formats as the schedule-first editor evolves.
- More robust LAN maintenance tools, including clearer validation feedback and safer recovery flows.
- Better media management, including optional preview improvements, bulk operations, and storage usage summaries.
- More scheduling features, such as date ranges, weekday rules, and reusable schedule templates.
- Optional device-oriented kiosk controls for deployments that need stricter lock-down behavior.

## Silent Schedules

Scheduled entries may use `mode: "silent"` to stop media playback for a time range.

```json
{
  "name": "silent-night",
  "start": "23:30",
  "end": "08:00",
  "mode": "silent",
  "screen": "allowSleep"
}
```

Supported `screen` values:

- `black`: stop playback and show a black fullscreen view, keeping the normal screen-on policy.
- `allowSleep`: stop playback, show black, clear `KEEP_SCREEN_ON`, and set an `AlarmManager.RTC_WAKEUP` alarm for the next playback window.

`allowSleep` lets Android turn off the LCD backlight according to the system screen timeout. It does not immediately lock the screen and does not require Device Admin permission. When the next non-silent schedule starts, the app tries to wake by relaunching `MainActivity` and restoring `TURN_SCREEN_ON` / `KEEP_SCREEN_ON`.

Use `samples/silent-scheduled-config.json` as the starting point.





