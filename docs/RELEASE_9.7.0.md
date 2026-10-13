# CineStream 9.7.0

Package `com.exor.cinestream` · version code 96 · Android 7.0/API 24 and newer.

## Changes

- Dark themed glass playback controls, animated Play/Pause, separate seekbar and compact time pill.
- Settings contains Speed and Lock; the speed picker remains Media3's native picker.
- Bottom-center swipe to unlock, live pinch percentage, and consistent gesture feedback that
  hides the whole controller without moving its upper feedback lane.
- Window-based phone/tablet sizing, compact narrow controls, large-text time layout, and bounded
  tablet docks/dialogs.
- Library permission recovery, labeled selection actions, cached previews/metadata and MKV
  header-discovery hardening from the current implementation.

## Downloads

- `CineStream-9.7.0-arm64-v8a.apk`: signed standalone APK for supported 64-bit ARM devices.
- `CineStream-9.7.0-armeabi-v7a.apk`: signed standalone APK for supported 32-bit ARM devices.
- `CineStream-9.7.0-play.aab`: signed Play upload bundle, containing both ARM variants.
- `CineStream-9.7.0-Play-Update.zip`: give this to the Play operator; it contains the AAB,
  listing copy, notes, screenshots, public certificate, verification and checksums.

The owner supplied new Explorer/Player artwork for the corresponding listing screenshots.
The two other screenshots and existing icon/feature graphic are retained. Screenshot contents
were not visually reviewed for this delivery; the owner/operator must review them before submission.

## Verification and limits

167 local unit tests, optimized ARM release builds, APK signature/alignment checks, fresh AAB
build/signature, bundletool validation, manifest/ABI inspection and ZIP byte-integrity checks
passed. All arm64 ELF LOAD segments are 16 KiB aligned. No new universal-device playback,
Dolby Vision color, fold-hinge, runtime 16 KiB, or screenshot-content validation is claimed.
The earlier 9.7.0 arm64 APK was installed/launched for the owner's manual test.

GitHub delivery is not Google Play submission or approval. The Play operator must compare
the active Upload key certificate, ensure code 96 is higher than every used code, and review
the Console results/declarations. Follow the [handover](../playstore/updates/9.7.0/HANDOVER.md).
