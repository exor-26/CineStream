# CineStream

CineStream is a local Android video player focused on fast on-device browsing and modern Android storage compatibility. It scans the device library through `MediaStore`, groups videos by folder, remembers playback progress, and plays content through a custom full-screen Media3 player with FFmpeg-backed codec support.

## Highlights

- Browse all device videos from a single library screen
- Switch between flat video list and folder-based navigation
- Search videos by title from the main library view
- Resume playback from the last watched position
- Show thumbnail, duration, size, quality, and basic video info
- Share videos through scoped-storage-safe `content://` URIs
- Rename and delete media through modern `MediaStore` flows
- Support external `ACTION_VIEW` playback intents
- Custom player gestures for brightness and volume
- Audio track selection, crop modes, and orientation lock controls

## Why this project matters

Older Android media apps often depend on raw file paths and broad storage permissions. That model breaks down on modern Android versions and creates Play Store policy risk. CineStream has been refactored to use `MediaStore` and content URIs as the primary storage model so it behaves correctly across Android 10 through Android 16-class devices.

## Tech stack

- Java
- Android SDK / Android Gradle Plugin
- AndroidX
- Media3 / ExoPlayer
- Jellyfin Media3 FFmpeg decoder extension
- Glide
- Material Components
- Palette API

## Project structure

- `app/src/main/java/com/example/cinestream/MainActivity.java`
  Main library screen, permission flow, MediaStore loading, folder grouping, rename/delete orchestration
- `app/src/main/java/com/example/cinestream/VideoAdapter.java`
  Video row binding, thumbnail loading, metadata display, share/info actions
- `app/src/main/java/com/example/cinestream/VideoPlayerActivity.java`
  Full-screen player, playback resume, gestures, crop/rotation/audio-track controls
- `app/src/main/java/com/example/cinestream/PlaybackPrefs.java`
  Lightweight playback progress persistence
- `app/src/main/java/com/example/cinestream/VideoFile.java`
  Media model built around stable IDs and `content://` URIs

## Storage model

CineStream now uses:

- `MediaStore` as the source of truth for device video discovery
- stable media IDs and `content://` URIs for playback and sharing
- `MediaStore` rename/delete mutation APIs for scoped storage compatibility
- runtime media permissions instead of `MANAGE_EXTERNAL_STORAGE`

This is the core architectural decision that keeps the app compatible with modern Android.

## Build requirements

- Android Studio with bundled JBR
- JDK 21 through Android Studio JBR is configured in `gradle.properties`
- Android SDK with `compileSdk 35`

## Build

```powershell
.\gradlew.bat assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Tested status

The current debug build has been verified on a connected physical Vivo V2504 (Vivo T4 Ultra).

Verified flows:

- app install and launch
- library loading from `MediaStore`
- folder mode
- player open from in-app library tap
- external `ACTION_VIEW` content-URI playback entry
- no runtime crashes during the verified core flow

Manual UX-oriented checks such as share target behavior, gesture feel, and destructive media actions should still be validated by hand before release.

## Contributing

Contributions are welcome. For anything substantial:

1. open an issue first for discussion
2. keep changes scoped and reviewable
3. run `.\gradlew.bat assembleDebug` before opening a pull request

Repository templates are included for:

- bug reports
- feature requests
- pull requests

## Releases

Project-facing release notes are tracked in [CHANGELOG.md](CHANGELOG.md).

## Current version

- `versionName`: `8.7`
- `versionCode`: `87`

## Roadmap ideas

- add proper release screenshots for GitHub and Play listing
- add instrumentation coverage for library and player entry flows
- improve long-press action automation coverage
- support richer subtitle and playback-speed controls
- add a dedicated empty state and media permission onboarding copy

## Repository hygiene

Generated local verification artifacts such as `cinestream-*.xml` UI dumps are ignored and should not be committed.
