# CineStream 9.7.0 handover verification

Performed October 13, 2026. Code source checkpoint:
`893f8e1fc6142f64cd7fde5609fc0308f0ff4d4b` (main/develop synchronized before bundle preparation).
Later handover/document commits do not change the app code in this bundle.

## Passed local checks

- `git diff --check`, staged-file scope and targeted private-key/token scans.
- 167 local unit tests: zero failures/errors. The bundle build reports the verified test task up-to-date.
- Gradle `testDebugUnitTest bundleRelease -PplayBundle=true`, release lint and R8.
- Native build targets for arm64-v8a and armeabi-v7a; existing FFmpeg inputs reused.
- JDK jarsigner validation: jar verified. Self-signed certificate/trust-chain and no-timestamp
  warnings are expected; they are not proof of acceptance by Play.
- Google bundletool 1.18.3 validation.
- Bundle manifest: com.exor.cinestream, 9.7.0, code 96, min SDK 24, target SDK 36.
- Legacy READ_EXTERNAL_STORAGE ends at API 32; READ_MEDIA_VIDEO and Android 14 selected-video
  access are declared. No INTERNET permission is present in the merged manifest.
- Bundle config uses PAGE_ALIGNMENT_16K for uncompressed native libraries.
- Only arm64-v8a and armeabi-v7a native outputs: five libraries per ABI, including
  libcinestream_ffmpeg.so, libdav1dJNI.so, libffmpegJNI.so, libvpx.so and libvpxV2JNI.so.
- All five arm64 libraries have ELF LOAD alignment 16384. The 32-bit libvpx.so has alignment
  4096; the other 32-bit libraries have 16384. No claim of runtime 16 KiB testing is made.
- R8 mapping and CineFFmpeg symbols for both ABIs are embedded in BUNDLE-METADATA.
- Signed standalone APK signatures and 16 KiB zipalign checks pass for both ARM APKs.
- Source scan found no playback decision using Build.MODEL/MANUFACTURER/HARDWARE/BOARD/DEVICE/
  PRODUCT or a LibVLC dependency. The technical About documentation is not a progress overlay.
- Explorer.png and Player.png match the packaged replacements byte-for-byte; retained track
  and privacy screenshots match the old files. No visual screenshot review was performed.
- Release notes: 362 characters. File identities and hashes are listed in SHA256SUMS.txt.

## Bundle identity

- Upload certificate SHA1: `65:F6:4C:32:53:06:35:FD:31:9B:93:2B:F9:AC:2A:31:5A:7C:FF:E5`
- Certificate SHA256: `CE:58:10:DE:F2:E2:9E:5A:27:EA:7B:68:0C:BE:C5:27:FF:C4:05:50:3D:9E:73:DD:6F:84:AE:B9:11:BD:7E:0C`
- AAB SHA256: `6E520BB6D34CECC4293256B2EEBFA5D38D59268668800E4AF33637AFF2EFCF1D`
- Signed AAB size: **19,660,297 bytes**. It includes both ARM variants; this is not an individual
  device's Play download size.

## Not verified here

Live upload-key activation, highest used Play version code, Console processing, declarations,
Google approval, screenshot contents/rights/current visible version text, or playback on all
phone/tablet/foldable configurations. No new physical-device playback tests were performed
for this packaging task. The earlier standalone 9.7.0 arm64 APK install/launch does not prove
the complete AAB/Google Play delivery path.

No GitHub Actions/CI were used for verification. Push commits carry skip-ci instructions.
No private keystore, password, or signing key belongs in the handover. The operator must
compare the active Upload key certificate and ensure code 96 is available before upload.

References: [Android bundle testing](https://developer.android.com/guide/app-bundle/test),
[GitHub workflow skipping](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs).
