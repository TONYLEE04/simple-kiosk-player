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
- OPPO Find X8 Pro, ColorOS 16: smoke-tested app startup and playback on a newer phone-class device.
- Huawei DBY-W09, HarmonyOS 4.2.0: smoke-tested app startup and playback on a newer tablet-class device.

Sleep/wake behavior has not been verified on every newer device. Test `screen: "allowSleep"` on the actual deployment device before relying on it, or use `screen: "black"` to keep the backlight policy simple.

Fullscreen and immersive behavior has not been fully verified on phone-style full-screen, cutout, or curved-screen devices. For kiosk deployments, prefer conventional tablet displays unless the target device has been tested.

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

## Release APK Signing

Release APKs must be signed with a private Android signing key. Keep this key backed up and private. If the key is lost, future APKs cannot upgrade installs that were signed with the old key.

The project reads release signing values from local `keystore.properties`. This file is ignored by Git. Use `keystore.properties.example` as the template.

Recommended key location:

```text
C:/Users/Tony Li/Documents/android-keys/simple-kiosk-player.jks
```

Create the key from PowerShell:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\Documents\android-keys"
& "D:\Apps Catalog\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\Documents\android-keys\simple-kiosk-player.jks" -alias simple-kiosk-player -keyalg RSA -keysize 2048 -validity 10000
```

Then copy `keystore.properties.example` to `keystore.properties` and replace the passwords. Use forward slashes in `storeFile`, for example:

```properties
storeFile=C:/Users/Tony Li/Documents/android-keys/simple-kiosk-player.jks
storePassword=your-store-password
keyAlias=simple-kiosk-player
keyPassword=your-key-password
```

Build the signed release APK. `assembleRelease` fails intentionally until `keystore.properties` is present and complete:

```powershell
$env:JAVA_HOME='D:\Apps Catalog\Android Studio\jbr'
.\gradlew.bat assembleRelease
```

The signed APK is written to:

```text
app/build/outputs/apk/release/app-release.apk
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


## First Run Setup

On a new device without `/sdcard/SimpleKiosk/config.json`, the app shows a setup screen instead of a generic error. The hidden maintenance gesture still works on this screen: tap the top-left corner 5 times within 10 seconds, tap `Start LAN`, then open the shown URL from another device on the same Wi-Fi network. Use the LAN page to upload media, create an all-day playlist schedule, and save `config.json`.

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

The LAN service is off by default and stops when the app process exits unless `management.autoStart` is enabled in `config.json`. It is for trusted local networks only.

## LAN Management Editor

The LAN management page includes a graphical editor for common maintenance tasks.

Current editor scope:

- Shows files from `/sdcard/SimpleKiosk/media/` and its subfolders in a searchable, filterable, paginated table.
- Treats media subfolders as groups, so a folder such as `/media/A-role/` can be added to the target schedule at once.
- Uploads single or multiple JPG, PNG, and MP4 files, optionally into a target subfolder.
- Shows best-effort metadata and red compatibility warnings for risky videos such as 4K-class, 60fps, HEVC/H.265, AV1, or high H.264 level files.
- Renames media files or relative media paths and updates matching `media/...` references in `config.json`.
- Deletes unreferenced media files. Referenced files are rejected instead of silently breaking playback.
- Adds all-day playlist, timed playlist, or silent schedule entries.
- Edits each playlist schedule's own playlist directly, including reorder, remove, type, image duration, and fit mode.
- Saves playlist presets in `playlistPresets` and applies them by copying into the selected schedule.
- Migrates a legacy top-level `playlist` into an all-day `00:00` to `00:00` schedule when opened in the LAN editor.
- Saves LAN-edited configs without a standalone top-level `playlist`.
- Validates schedule times before saving and requires strict `HH:mm` values such as `06:00`, not `6:00`.
- Saves the updated JSON back to `/sdcard/SimpleKiosk/config.json`.
- Rolls back to `/sdcard/SimpleKiosk/config.json.bak` if the previous save should be restored.

Videos always play to completion. The duration field is shown only for image items.

After saving, the LAN page calls `POST /control/apply-config` so touchless devices apply the new schedule immediately. The normal config hot reload path remains as a fallback.

LAN access protection is on by default while the management server is running. The tablet maintenance view shows a URL with a one-time access code for the current app process. If `management.password` is configured, the login page also accepts that fixed local password. The web page can temporarily disable or re-enable this protection for trusted local networks.

Thumbnail previews are served locally by the app. Image files are downsampled before being sent to the browser. MP4 files use a best-effort first-frame preview and fall back to a lightweight placeholder if the old device cannot extract a frame. The media table only loads thumbnails for the current page to keep large libraries usable.

Compatibility warnings are advisory. They do not block adding media to schedules, because newer devices may play files that old Android 4.4 tablets cannot. For legacy tablets, prefer MP4 H.264, 720p or 1080p, 30fps, yuv420p, and AAC audio.

`logs/player.log` rotates at 1 MB and keeps one backup at `logs/player.log.1` to avoid unbounded growth during long-running playback.

The LAN management page embeds the official Keep Android Open banner from `keepandroidopen.org` when the browser can reach the public internet. Playback, upload, editing, and local management continue to work if that external script cannot load.

## Sealed Enclosure Operation

For devices installed in a case where the screen cannot be touched, add an optional `management` block to `/sdcard/SimpleKiosk/config.json`:

```json
{
  "management": {
    "autoStart": true,
    "password": "123456"
  }
}
```

`autoStart` defaults to `false`. When it is `true`, the app starts the LAN management server after loading a valid config. `password` is optional; if it is missing or empty, access uses the existing temporary code shown on the tablet maintenance screen. The fixed password is stored only in the local config file, not hard-coded into the APK.

The LAN page shows a Device control section for touchless operation:

- `Apply config now`: reload `config.json` and re-evaluate the active schedule immediately.
- `Black screen now`: highest-priority runtime override that stops playback and shows black.
- `Allow sleep now`: highest-priority runtime override that stops playback and clears `KEEP_SCREEN_ON` so Android may turn the display off.
- `Resume schedule`: clears the runtime override and returns to the current schedule.

Manual overrides are not written to `config.json`. Rebooting the app restores normal schedule behavior.

If LAN management must remain reachable at any time, prefer `screen: "black"` for silent schedules. `screen: "allowSleep"` saves more power, but Android may slow or suspend CPU and Wi-Fi while the display is off, so the LAN page is not guaranteed to respond until the device is woken, for example by pressing the power button.

## Future Local Network Setup

The runtime source of truth is still `/sdcard/SimpleKiosk/config.json`.

LAN maintenance writes the same config file and media directory instead of introducing a separate cloud or account system. This keeps playback offline-first and lets the existing reload path apply changes without restarting the app.

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

For deployments that need reliable LAN maintenance during silent hours, use `screen: "black"` instead. `screen: "allowSleep"` is best treated as a power-saving mode, not an always-online maintenance mode.

Use `samples/silent-scheduled-config.json` as the starting point.
