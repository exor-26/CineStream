# CineStream

CineStream is a local Android video player focused on fast on-device browsing, broad codec recovery, and modern Android storage compatibility. It scans the device library through `MediaStore`, remembers playback progress, and uses Media3 with CineStream's bundled FFmpeg decoder—never VLC or LibVLC.

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
- Hardware-first playback with direct CineFFmpeg software recovery
- Runtime-governed H.264 compatibility playback for unsupported or unsustainable video
- Reusable progressive compatibility segments for faster replay and seeking
- Original audio, subtitle, metadata, and track-selection sources remain separate from compatibility video

## Video compatibility flow

Playback decisions use runtime codec results, memory pressure, CPU load, thermal state, source complexity, and measured decoding speed. They do not use manufacturer, model, chipset, or serial-number allowlists.

```text
MediaCodec hardware playback
→ direct CineFFmpeg recovery
→ governed software playback
→ progressive H.264 compatibility cache
→ full-file H.264 fallback
```

Completed compatibility files are reused on replay. Only finalized, structurally validated H.264 MP4 segments enter the playable timeline; incomplete `.part` files are never exposed to the player.

## Why this project matters

Older Android media apps often depend on raw file paths and broad storage permissions. That model breaks down on modern Android versions and creates Play Store policy risk. CineStream has been refactored to use `MediaStore` and content URIs as the primary storage model so it behaves correctly across Android 10 through Android 16-class devices.

## Tech stack

- Java
- Android SDK / Android Gradle Plugin
- AndroidX
- Media3 / ExoPlayer
- Jellyfin Media3 FFmpeg decoder extension
- CineStream native FFmpeg video decoder and Dolby Vision Profile 5 reshape path
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

The current release flow has been exercised on physical Vivo, Realme, and older Android devices. The exact 7680×4320 60 fps Dolby Vision Profile 5 test file was also used for compatibility and replay validation.

Verified flows:

- app install and launch
- library loading from `MediaStore`
- folder mode
- player open from in-app library tap
- external `ACTION_VIEW` content-URI playback entry
- no runtime crashes during the verified core flow
- completed progressive segments are reused during replay
- H.264 compatibility output is selected by the device hardware decoder

An 8K Dolby Vision source can exceed the realtime software throughput of older devices. CineStream preserves original audio while preparing compatibility video and caches completed work; it does not claim realtime 8K decoding where the hardware and CPU cannot physically sustain it.

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

Signed release artifacts are attached to each GitHub Release:

- `CineStream-9.3-arm64-v8a.apk`
- `CineStream-9.3-armeabi-v7a.apk`

For most modern phones, use the `arm64-v8a` build.

## Current version

- `versionName`: `9.3`
- `versionCode`: `88`

## Release packaging

Release builds are now published as split APKs instead of one universal APK.

- `arm64-v8a` release APK: optimized for most current Android phones
- `armeabi-v7a` release APK: fallback for older 32-bit devices

Version 9.3 removes a redundant native metadata-only FFmpeg/OpenSSL bundle while retaining CineFFmpeg video, FFmpeg audio, dav1d, VP9, Media3, and compatibility playback. The signed arm64 build is approximately 14 MB.

## Roadmap ideas

- add proper release screenshots for GitHub and Play listing
- add instrumentation coverage for library and player entry flows
- improve long-press action automation coverage
- support richer subtitle and playback-speed controls
- add a dedicated empty state and media permission onboarding copy

## Repository hygiene

Generated local verification artifacts such as `cinestream-*.xml` UI dumps are ignored and should not be committed.
