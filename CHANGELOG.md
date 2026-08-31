# Changelog

All notable changes to this project should be documented in this file.

## 9.6 - 2026-08-31

### Added

- Android picture-in-picture for video that is actively playing when Home is pressed
- High-contrast Previous video, Play/Pause, and Next video actions in the PiP window
- Media3 session integration for system playback state and controls

### Changed

- A paused full-screen video remains in the app instead of opening an inactive PiP window
- Expanding PiP preserves whether playback was playing or paused
- The player window, shutter, and retained video frame stay black during PiP surface transitions

### Fixed

- Closing the PiP window no longer leaves hidden video or audio playback running
- PiP controls remain visible against both light and dark video content
- Expanding from PiP no longer exposes the app's light window background while the video surface resizes

## 9.5.1 - 2026-08-29

### Changed

- The audio/subtitle sheet keeps its translucent glass material without requesting an expensive live system blur over the actively rendering video
- The track sheet starts expanded immediately instead of visibly transitioning through a second opening state

### Fixed

- The most recently played video is again pinned first while the selected Name, Date, or Duration sort applies to every item beneath it
- Opening the audio/subtitle sheet no longer introduces the new blur-related playback hitch

## 9.5 - 2026-08-29

### Added

- Persistent library sorting by name, newest date, or duration
- Long-press multi-selection with compact rename, share, details, and delete actions
- Scoped-storage-safe multi-video sharing and batch deletion
- Theme-aware selection indicators on video cards

### Changed

- Library chrome, dialogs, sheets, and player feedback now share one transparent frosted-glass visual system
- The About surface now provides structured project documentation, the full developer name, and a guarded repository action
- Audio and subtitle selections use the active system theme for text, selection, and check states
- Pinch zoom scales the complete Media3 content frame and reports a practical live percentage
- Crop modes remain direct one-tap actions with animated feedback and distinct Original, Fill, and Fit behavior
- Full-screen back and Home gestures retain Android system navigation while providing a standard second-swipe exit hint

### Fixed

- Upward track-list scrolling being interpreted as sheet dismissal after lifting and touching again
- Landscape lock control placement conflicting with display cutouts
- Zoom expanding vertically while leaving unintended horizontal voids
- Stale single-item actions remaining available during multi-selection
- Completed library sorting being lost after media refresh or playback return

## 9.4 - 2026-08-29

### Added

- Continuous two-finger pinch zoom with bounded aspect-safe video-surface scaling and live percentage feedback
- One-finger horizontal seek with duration-aware mapping, logical-timeline clamping, signed delta, and target timestamp preview
- Screen lock with deliberate bottom-left left-to-right swipe unlock, haptic confirmation, and transient unlock guidance
- Right-half stationary touch-and-hold temporary 2× playback with exact Media3 playback-parameter restoration on release or cancellation
- Direct crop-mode cycling through the existing Original → Fill → Fit sequence with transient mode feedback
- Professional CineStream Dev information view with a guarded clickable repository entry

### Changed

- Player-surface interactions now use sticky gesture ownership so pinch, seek, brightness, volume, temporary speed, lock, and taps do not steal an already classified gesture
- Gesture feedback uses coordinated transient presentation and respects the system animator setting where supported
- Crop selection resets continuous zoom to the selected mode's 100% baseline and avoids aspect-ratio stretching
- High-resolution CineFFmpeg recovery now uses a bounded, memory-aware decoder thread pool and decoder-safe fast mode without deleting presentation frames
- Software playback starts after a real decoded frame is available, then continues under Media3 timestamp, A/V-sync, and frame-drop control
- Unsustainable direct playback releases its source decoder before H.264 compatibility generation so playback and export do not compete for the same CPU and native memory
- Partial progressive outputs stay outside the playable timeline until they can be represented as one coherent Media3 period; completed full-file compatibility cache remains the stable fallback

### Fixed

- Audio/subtitle track lists now retain scroll ownership across separate upward and downward gestures while allowing sheet dismissal only from the real list top
- Black 10-bit HDR output caused by incompatible GLES sampler types sharing texture unit zero
- Runtime MediaCodec lifecycle failures that were not previously classified as recoverable video failures
- False 100% software drop reports caused by `DecoderVideoRenderer` not forwarding rendered-frame counts
- Multi-second frame gaps caused by discarding non-reference HEVC frames in governed software mode
- Repeated black handoffs and severe memory/CPU contention when direct high-resolution software playback and compatibility generation ran concurrently

## 9.3 - 2026-08-24

### Added

- Hardware-first video recovery ladder using MediaCodec, CineFFmpeg, progressive H.264 compatibility segments, and full-file fallback
- Runtime resource governor based on measured memory, CPU, thermal, source, display, decoder, and playback conditions
- Reusable progressive compatibility cache with validated atomic segment promotion
- Dolby Vision Profile 5 stream-driven reshape support in the compatibility decoder path

### Changed

- Progressive segments now use independently playable MP4 containers for consistent parsing across Android versions
- Replay cache discovery runs independently of library and thumbnail work
- Progressive generation resumes at the safest quality tier already proven by cached segments
- Original audio remains active while full-file compatibility video is prepared
- Removed a redundant metadata-only native FFmpeg/OpenSSL bundle, reducing release size without removing playback codecs

### Fixed

- Pre-Android-12 release crash caused by R8 merging a newer media-metrics signature into an older class-loading path
- Blank replay caused by completed progressive fragments being rejected or delayed during cache discovery
- Repeated replay regeneration at an unnecessarily high compatibility tier
- 480p30 being treated as if the final 480p24 recovery tier had already been measured

## 9.2 - 2026-03-11

### Added

- About dialog on the main screen with project, developer, and license details
- Dual-pane track picker in the player for captions and audio tracks
- Rounded HUD progress drawables for volume, boost volume, and brightness overlays

### Changed

- Refreshed player control icons to better match the existing glass-style UI
- Enabled previous and next playback controls to follow the same sequence as the video list
- Extended player volume gestures to show base volume and post-100 boost range in the HUD

### Fixed

- RecyclerView video-card crashes caused by heavy bind-time metadata and thumbnail work
- Player top overlay dimming that made video playback appear faded from the upper edge
- Player controller and custom control hide-show sync when tapping blank screen areas
- Video rows intermittently failing to open after repeated open-close cycles
- Detailed media info crash for some phone-recorded videos
- Volume and brightness HUD flicker during repeated swipe adjustments
- Rounded progress bars now render correctly instead of showing sharp ends

## 8.7 - 2026-03-10

### Added

- GitHub-ready project documentation and repository templates
- Detailed inline code comments for the core library and player flow
- Stable playback persistence keyed by media identity instead of raw file paths

### Changed

- Refactored local media browsing to use `MediaStore` content URIs as the primary storage model
- Updated library, playback, share, rename, and delete flows for scoped storage compatibility
- Modernized system bar handling and removed deprecated Android UI APIs
- Moved Java compilation to Java 17 and pinned Gradle to Android Studio JBR

### Fixed

- Broken share flow caused by path-based URIs
- Android 10+ storage compatibility issues caused by raw file path usage
- Manifest and build warnings related to outdated storage and deprecated APIs
