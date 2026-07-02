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

## Future Local Network Setup

The runtime source of truth is still `/sdcard/SimpleKiosk/config.json`.

A future LAN-only maintenance page should write the same config file and media directory instead of introducing a separate cloud or account system. This keeps playback offline-first and lets the existing hot reload path apply changes without restarting the app.
