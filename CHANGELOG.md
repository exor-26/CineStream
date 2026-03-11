# Changelog

All notable changes to this project should be documented in this file.

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
