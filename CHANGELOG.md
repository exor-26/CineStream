# Changelog

All notable changes to this project should be documented in this file.

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

