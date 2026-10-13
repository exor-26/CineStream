# CineStream 9.6.3 release verification

Performed October 3, 2026. These are build and static artifact checks, not app/device tests or Play approval.

## Results

- `git diff --check`: passed; existing Windows line-ending warnings are not whitespace errors.
- `.\gradlew.bat bundleRelease -PplayBundle=true --console=plain`: passed, including release lint and R8 shrinking.
- Native build tasks for `arm64-v8a` and `armeabi-v7a`: passed using existing FFmpeg inputs.
- Signed AAB verified by JDK `jarsigner`: passed. The upload certificate is self-signed; the expected JAR trust-chain/no-timestamp warnings do not indicate a broken signature.
- Google bundletool 1.18.3 `validate`: passed.
- Bundle manifest: package `com.exor.cinestream`, version name `9.6.3`, version code `95`, minimum SDK `24`, target SDK `36`.
- Video permissions include legacy storage access through Android 12, `READ_MEDIA_VIDEO`, and Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`. No `INTERNET` permission is present in the final merged manifest.
- Bundle config requests `PAGE_ALIGNMENT_16K` for uncompressed native libraries.
- Each ARM ABI contains `libcinestream_ffmpeg.so`, `libdav1dJNI.so`, `libffmpegJNI.so`, `libvpx.so`, and `libvpxV2JNI.so`. No x86 ABI is included.
- Every arm64 library's ELF LOAD alignment is `0x4000` (16 KiB). The 32-bit `libvpx.so` uses `0x1000`; other 32-bit libraries use `0x4000`. This is not a claim that all libraries are 16 KiB aligned or that runtime 16 KiB behavior was tested.
- R8 mapping and CineFFmpeg native symbols for both ABIs are embedded in bundle metadata, so no duplicate mapping upload is needed unless Console requests one.
- Four phone screenshots: 941 x 1672, RGB/24-bit PNG, no alpha. Icon: 512 x 512. Feature graphic: 1024 x 500. The phone images satisfy basic Play screenshot dimensions; they are not the optional four 1080 x 1920 images recommended for promotional eligibility.

## Signing identity

- SHA1: `65:F6:4C:32:53:06:35:FD:31:9B:93:2B:F9:AC:2A:31:5A:7C:FF:E5`
- Certificate SHA256: `CE:58:10:DE:F2:E2:9E:5A:27:EA:7B:68:0C:BE:C5:27:FF:C4:05:50:3D:9E:73:DD:6F:84:AE:B9:11:BD:7E:0C`
- AAB SHA256: `4018CEF231DE44BD67013A51526731F7E01F40F8B5F3DF6C57FCD92DCC86D28D`
- AAB size: `19,409,023 bytes`.

Compare the upload certificate against the existing app's live Play Console settings. The separate Google app signing certificate may differ. No live Console activation or submission was verified.

## Boundaries

No unit, emulator, physical-device, permission-dialog, or playback tests were performed during this release preparation, as requested. No GitHub push or Play Console publication was performed. Older artifacts and keystores were preserved. Playback, audio, subtitles, native decoder source, and compatibility logic were not edited for this release.

The publisher still must check version-code availability, manual acceptance, declarations, and Console processing/review.

References: [Google Play preview assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en), [Android 16 KiB page-size guidance](https://developer.android.com/guide/practices/page-sizes), [Google bundletool](https://github.com/google/bundletool/releases/tag/1.18.3).
