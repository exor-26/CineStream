# CineStream

**A free, open-source Android player for videos on your device.**

CineStream brings local videos into a searchable MediaStore library, remembers playback progress, and uses Media3 hardware playback with an on-device FFmpeg recovery path for difficult files.

[Google Play](https://play.google.com/store/apps/details?id=com.exor.cinestream) · [APK releases](https://github.com/exor-26/CineStream/releases) · [Playback architecture](#video-compatibility-flow) · [Build from source](#build)

An independent product by [Aditya Kumar](https://exor-portfolio.netlify.app/), covering product direction, Android architecture, playback integration, and release delivery.

## Highlights

- Browse all device videos from a single library screen
- Request Android video permission on first launch, then show clear recovery actions if access is denied
- Respect Android 14+ selected-video access with a visible Manage access action
- Switch between flat video list and folder-based navigation
- Search videos by title from the main library view
- Sort the library by name, newest date, or duration
- Resume playback from the last watched position
- Show thumbnail, duration, size, quality, and basic video info
- Long-press to select one or many videos with labeled Rename, Share, Info, and Delete actions
- Share multiple videos through scoped-storage-safe `content://` URIs
- Rename, inspect, and batch-delete media through modern `MediaStore` flows
- Support external `ACTION_VIEW` playback intents
- Continue active playback in Android picture-in-picture with Previous, Play/Pause, Next, Expand, and Close controls
- Continuous two-finger pinch zoom with a live percentage preview
- One-finger horizontal seek with signed delta and target-time preview
- Left-side brightness and right-side volume gestures with sticky gesture ownership
- Right-half touch-and-hold temporary 2× playback with exact prior-speed restoration
- Settings with Speed and Lock; deliberate bottom-center left-to-right swipe unlock
- Direct crop-mode cycling without a popup, preserving source aspect ratio
- Reliable bidirectional audio/subtitle track-list scrolling inside the player sheet
- Professional CineStream Dev information view with guarded repository opening
- Theme-aware frosted-glass library, dialogs, sheets, and player feedback
- Audio track selection, captions, orientation controls, loudness enhancement, and volume boost
- Hardware-first playback with direct CineFFmpeg software recovery
- Runtime-governed H.264 compatibility playback for unsupported or unsustainable video
- Validated compatibility caching for stable, faster replay
- Original audio, subtitle, metadata, and track-selection sources remain separate from compatibility video

## Player gesture arbitration

Player surface gestures use one ownership model so an interaction does not change meaning after it has been classified:

```text
Locked state: unlock swipe only
→ two or more pointers: pinch zoom
→ stationary right-half hold: temporary 2×
→ one-finger horizontal drag: seek
→ one-finger vertical drag: brightness (left) / volume (right)
→ unclassified tap: normal controller interaction
```

The lock state disables player controls and app-level gestures without trapping Android system navigation. Temporary 2× playback snapshots the exact active Media3 playback parameters and restores them on release, cancellation, media transition, pause, end, player replacement, or lifecycle teardown.

## Video compatibility flow

Playback decisions use runtime codec results, memory pressure, CPU load, thermal state, source complexity, and measured decoding speed. They do not use manufacturer, model, chipset, board, product, serial, or device-name playback rules.

```text
MediaCodec hardware playback
→ direct CineFFmpeg recovery
→ governed software playback
→ completed H.264 compatibility cache
→ full-file H.264 recovery
```

Completed compatibility files are reused on replay. Partial progressive outputs remain isolated from the playable timeline until Media3 can represent them as one coherent period; incomplete `.part` files are never exposed to the player.

For high-resolution sources that cannot remain near realtime, CineStream measures actual rendered and dropped frames, stops the unsustainable source decoder, and gives compatibility generation the available CPU and memory. This avoids running two expensive decoders concurrently and preserves Media3's original audio, subtitle, metadata, and synchronization sources for the final playback session.

## Why this project matters

Older Android media apps often depend on raw file paths and broad storage permissions. That model breaks down on modern Android versions and creates Play Store policy risk. CineStream uses `MediaStore` and content URIs as the primary storage model so it behaves correctly across current Android storage models.

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

- `app/src/main/java/com/exor/cinestream/MainActivity.java`
  Main library screen, permission flow, MediaStore loading, folder grouping, rename/delete orchestration
- `app/src/main/java/com/exor/cinestream/VideoAdapter.java`
  Video row binding, thumbnail loading, metadata display, share/info actions
- `app/src/main/java/com/exor/cinestream/VideoPlayerActivity.java`
  Full-screen player, playback resume, brightness/volume execution, rotation, tracks, and compatibility lifecycle
- `app/src/main/java/com/exor/cinestream/UnifiedPlayerView.java`
  Player-surface gesture arbitration, screen lock, pinch zoom, logical seeking, temporary speed, crop cycling, and transient gesture feedback
- `app/src/main/java/com/exor/cinestream/PlaybackPrefs.java`
  Lightweight playback progress persistence
- `app/src/main/java/com/exor/cinestream/VideoFile.java`
  Media model built around stable IDs and `content://` URIs

## Storage model

CineStream uses:

- `MediaStore` as the source of truth for device video discovery
- stable media IDs and `content://` URIs for playback and sharing
- `MediaStore` rename/delete mutation APIs for scoped storage compatibility
- runtime media permissions instead of `MANAGE_EXTERNAL_STORAGE`

On a first opening without video access, CineStream launches Android's permission dialog. If access is denied, the library area explains why permission is needed and offers **Allow access**. When Android no longer permits another prompt, **Open settings** opens the app's system settings. Android 14+ selected-video access shows only the permitted videos and offers **Manage access** to adjust the selection.

## Build requirements

- Android Studio with bundled JBR
- JDK 17 or newer (Android Studio's bundled JBR is supported)
- Android Gradle Plugin `8.9.1` with Gradle `8.11.1`
- Android SDK with `compileSdk 36`
- Android NDK `26.1.10909125`
- CMake `3.22.1`

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

For the Google Play Android App Bundle:

```powershell
.\gradlew.bat testDebugUnitTest bundleRelease -PplayBundle=true
```

Release builds are split by ABI:

- `arm64-v8a`
- `armeabi-v7a`

The release packaging intentionally does not generate a universal APK.

## Tested status

The core release flow has previously been exercised on physical Android hardware, including app launch, `MediaStore` library loading, player entry, external `ACTION_VIEW` playback, compatibility-cache reuse, and hardware playback of generated H.264 compatibility output.

Gesture feel, lock/unlock ergonomics, pinch behavior, temporary-speed timing, track-sheet touch arbitration, and destructive media actions should be validated manually on physical hardware before publishing a release. Source-only or host-side verification must not be described as physical-device testing.

## Contributing

Contributions are welcome. For anything substantial:

1. open an issue first for discussion
2. keep changes scoped and reviewable
3. run `.\gradlew.bat testDebugUnitTest assembleRelease` before opening a pull request

## Releases

Project-facing release notes are tracked in [CHANGELOG.md](CHANGELOG.md).

Download the signed APK for your device from [CineStream 9.7.0](https://github.com/exor-26/CineStream/releases/tag/v9.7.0):

- `arm64-v8a` for almost all current 64-bit Android devices
- `armeabi-v7a` for supported older 32-bit Android devices

The APKs are split by ABI to keep the installed download compact. CineStream does not ship a universal APK or include VLC/LibVLC.

## Current version

- `versionName`: `9.7.0`
- `versionCode`: `96`

Version 9.7.0 is the current release source. GitHub delivery and Google Play submission are
separate: a GitHub release does not mean the Play update has been submitted or approved.
The phone/tablet window-sizing map and verification boundaries are documented in
[docs/SCREEN_ADAPTATION.md](docs/SCREEN_ADAPTATION.md).

## Release packaging

Release builds use split APKs:

- `arm64-v8a` for current 64-bit Android hardware
- `armeabi-v7a` for supported 32-bit Android hardware

Version 9.6 adds lifecycle-safe picture-in-picture for actively playing videos. Home continues playback in the floating window, explicit Back exits normally, expanding preserves Play/Pause state, and closing PiP stops and releases playback. High-contrast Previous, Play/Pause, and Next actions remain usable across light and dark system themes. The hardware-first, OEM-independent recovery architecture remains unchanged.

Version 9.6.1 targets Android 16/API 36 for Google Play submission and exposes the public privacy policy from the in-app About surface. Google Play receives one Android App Bundle and generates reduced, ABI-specific downloads for each device.

Version 9.6.2 uses the production package name `com.exor.cinestream` and labels videos stored directly at the shared-storage root as `Internal storage`.

Version 9.6.3 improves video-permission recovery and adds readable labels beneath the four library selection actions. The update handover is documented in [playstore/updates/9.6.3/HANDOVER.md](playstore/updates/9.6.3/HANDOVER.md), including separate Play listing artwork and release notes. This update does not introduce playback or codec changes.

Version 9.7.0 introduces dark, themed glass playback controls. Settings offers Speed and Lock;
Speed retains Media3's existing picker. Gesture feedback hides the entire controller together,
uses a stable upper lane, and includes a live pinch-zoom percentage. Narrow windows retain
48dp button targets, short landscape windows reposition the controls between the toolbar and
dock, large-text timestamps can stack inside their pill, and tablet docks/dialogs have bounded
widths. The media surface, original audio system, and decoder pipeline are preserved by these
controller and screen-sizing changes. Previous release bundles and their artwork remain
historical artifacts; they must not be relabeled as 9.7.0.

The current Google Play operator package is documented in
[playstore/updates/9.7.0/HANDOVER.md](playstore/updates/9.7.0/HANDOVER.md).
Its signed AAB and listing images are separate upload inputs. The owner's new Explorer and
Player artwork replaces the corresponding screenshots; visual review is left to the owner/operator.

## Roadmap ideas

- add instrumentation coverage for library and player entry flows
- extend physical-device gesture regression coverage
- support richer subtitle controls
- add a dedicated empty state for an accessible library with no videos

## Repository hygiene

Generated local verification artifacts such as `cinestream-*.xml` UI dumps are ignored and should not be committed.
