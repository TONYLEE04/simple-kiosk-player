# PROJECT_SPEC.md

# Simple Kiosk Player

Simple Kiosk Player is a lightweight offline Android media player for turning old tablets into kiosk-style display screens.

It is designed for local files, JSON playlists, scheduled playback, fullscreen display, and long-running use on old Android tablets.

It does not require cloud services, accounts, Google Play Services, ads, subscriptions, or network access.

## Initial Target Device

Primary target:

* Samsung SM-T331C
* Android 4.4.2
* Tablet display around 1280x800
* Used as a dynamic display screen inside a figure cabinet
* Device may remain plugged in for long periods
* Media files are updated through USB from a Windows PC

## MVP Features

### v0.1 Playback

Implement:

* Read `/sdcard/SimpleKiosk/config.json`.
* Parse JSON config.
* Play local image files.
* Play local MP4 video files.
* Loop through playlist items.
* Support image duration.
* For videos, move to next item when video completes.
* Support global and per-item fitMode.
* Run fullscreen.
* Keep screen on.
* Hide system UI where possible.
* Show error screen if config or media is invalid.
* Write logs to `/sdcard/SimpleKiosk/logs/player.log`.

Supported media types for v0.1:

* image: jpg, jpeg, png
* video: mp4, preferably H.264

GIF support is not required in v0.1.

### v0.2 Scheduling

Add:

* Multiple scheduled playlists.
* Time ranges using `HH:mm`.
* Cross-midnight ranges, such as 22:30 to 08:00.
* Periodic schedule checking while app is running.
* Config hot reload when `config.json` changes.

### v0.3 Kiosk Improvements

Add:

* BootReceiver for auto-start after boot.
* Hidden maintenance gesture.
* Maintenance screen.
* Reload config button.
* View logs screen.
* Optional PIN.
* Basic touch lock.

## Config Format v1

Example:

```json
{
  "version": 1,
  "settings": {
    "orientation": "landscape",
    "fitMode": "contain",
    "background": "#000000",
    "keepScreenOn": true,
    "hideSystemUi": true,
    "mute": true
  },
  "playlist": [
    {
      "type": "image",
      "file": "media/intro.png",
      "duration": 8,
      "fitMode": "contain"
    },
    {
      "type": "video",
      "file": "media/demo.mp4",
      "fitMode": "cover"
    }
  ]
}
```

Scheduled example:

```json
{
  "version": 1,
  "settings": {
    "orientation": "landscape",
    "fitMode": "contain",
    "background": "#000000",
    "keepScreenOn": true,
    "hideSystemUi": true,
    "mute": true
  },
  "schedules": [
    {
      "name": "day",
      "start": "08:00",
      "end": "22:30",
      "playlist": [
        {
          "type": "video",
          "file": "media/day.mp4",
          "fitMode": "cover"
        }
      ]
    },
    {
      "name": "night",
      "start": "22:30",
      "end": "08:00",
      "playlist": [
        {
          "type": "image",
          "file": "media/night.png",
          "duration": 60,
          "fitMode": "contain"
        }
      ]
    }
  ]
}
```

## Fit Mode Semantics

* contain: preserve aspect ratio and show entire media.
* cover: preserve aspect ratio and fill the screen, cropping allowed.
* stretch: fill the screen without preserving aspect ratio.
* center: center content.

Images may use ImageView scale types.

Videos should preferably use TextureView and matrix scaling rather than VideoView, so that fit modes can be implemented consistently.

## Non-Goals

Do not implement in the first versions:

* Cloud dashboard.
* Remote device management.
* User accounts.
* Analytics.
* Ads.
* Subscriptions.
* Google Play Services.
* Modern Material UI.
* WebView-based playback.
* Multi-device fleet management.
* Network sync.
* Enterprise kiosk lockdown.

## Honest Kiosk Limitation

On Android 4.4, the app cannot fully block Home, Recent, or hardware/system navigation keys without root or device-specific system modification.

The app should provide app-level kiosk behavior only:

* fullscreen
* keep screen on
* hide system UI where possible
* intercept Back key
* ignore accidental touches
