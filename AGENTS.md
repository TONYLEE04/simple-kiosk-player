# AGENTS.md

## Project Identity

This project is a lightweight offline Android kiosk media player.

Its purpose is to turn old Android tablets into simple looping display screens for cabinets, shelves, exhibitions, public displays, or personal collections.

The project should prioritize reliability, simplicity, old-device compatibility, and local-file playback.

## Hard Technical Constraints

* Language: Java only.
* Minimum Android version: Android 4.4 / API 19.
* Use native Android Views.
* Do not use Kotlin.
* Do not use Jetpack Compose.
* Do not use AndroidX unless absolutely unavoidable.
* Do not use Google Play Services.
* Do not use cloud services.
* Do not require login, accounts, subscriptions, ads, telemetry, or network access.
* Do not build a remote management platform.
* Do not add unnecessary abstractions or enterprise architecture.

## Core Goal

Build a simple Android app that:

* Reads a local JSON config file.
* Plays local media files from storage.
* Supports images and videos.
* Loops through playlists.
* Supports basic scheduled playback.
* Runs fullscreen with the screen kept on.
* Handles missing files and config errors gracefully.
* Works well on old tablets with different screen resolutions.

## Target Storage Layout

The app should prefer this directory on legacy Android devices:

/sdcard/SimpleKiosk/

Expected structure:

/sdcard/SimpleKiosk/
config.json
media/
logs/

## Compatibility Priorities

Priority 1:

* Android 4.4 legacy tablets.
* Local file playback.
* H.264 MP4 video.
* JPG and PNG images.
* 1280x800 and similar tablet screens.

Priority 2:

* Newer Android versions.
* Higher-resolution screens.
* Optional future support for scoped storage or SAF.

## UI Principles

The app should have no normal interactive UI during playback.

Playback mode should be:

* Fullscreen.
* Black background.
* Immersive where supported.
* Keep screen on.
* Hide system UI where possible.
* Ignore accidental touches during playback.
* Allow a hidden maintenance gesture later.

## Media Fit Modes

Support these fit modes:

* contain: preserve aspect ratio and show entire media, black bars allowed.
* cover: preserve aspect ratio and fill screen, cropping allowed.
* stretch: fill screen without preserving aspect ratio.
* center: show centered content.

## Error Handling

The app must not crash for common user mistakes.

Handle:

* Missing config file.
* Invalid JSON.
* Missing media file.
* Unsupported media file.
* Empty playlist.
* Invalid schedule.
* Video playback failure.
* Image decode failure.

Show a clear fullscreen error message and write logs to:

/sdcard/SimpleKiosk/logs/player.log

## Development Style

Prefer straightforward Java classes.

Avoid clever abstractions.

Keep the code readable for a single maintainer.

Do not introduce features outside the requested task.

When implementing a task, make the smallest coherent change that satisfies the acceptance criteria.

## Build Expectations

The project should build with Gradle as a normal Android project.

If the build environment cannot support very old Android Gradle Plugin versions, explain the limitation and choose the simplest practical Gradle configuration while preserving minSdk 19.
