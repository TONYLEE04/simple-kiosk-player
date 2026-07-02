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

The app supports either a top-level `playlist` or scheduled playlists through `schedules`.

Use `samples/scheduled-config.json` as the starting point for scheduled playback. Time values use `HH:mm` in the device local time. Cross-midnight ranges are supported, for example `22:30` to `08:00`.

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

The first LAN page is intentionally small. It can show status, logs, current config, media files, and upload media. Full graphical playlist editing will build on this later.

The LAN service is off by default and stops when the app process exits. It is for trusted local networks only.
## Future Local Network Setup

The runtime source of truth is still `/sdcard/SimpleKiosk/config.json`.

A future LAN-only maintenance page should write the same config file and media directory instead of introducing a separate cloud or account system. This keeps playback offline-first and lets the existing hot reload path apply changes without restarting the app.

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



