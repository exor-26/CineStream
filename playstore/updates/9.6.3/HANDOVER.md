# CineStream 9.6.3 update handover

Prepared October 3, 2026 for the existing Google Play app `com.exor.cinestream`.

## Give the Play Console operator

Send `CineStream-9.6.3-Play-Update.zip`. Its bundle, text, and graphic files are separate upload inputs:

| File | Where it belongs |
| --- | --- |
| `CineStream-9.6.3-play.aab` | New release for the existing app |
| `whats-new-en-US.txt` | English (United States) release notes |
| `store-listing-en-US.txt` | Optional updated English listing copy |
| `screenshots/01-library.png` | Phone screenshot 1: device library |
| `screenshots/02-tracks.png` | Phone screenshot 2: audio and subtitles |
| `screenshots/03-controls.png` | Phone screenshot 3: playback controls |
| `screenshots/04-privacy.png` | Phone screenshot 4: local and private playback |
| `graphics/play-icon-512x512.png` | Play icon, if the operator needs it |
| `graphics/feature-graphic-1024x500.png` | Feature graphic, if the operator needs it |
| `SHA256SUMS.txt` | File integrity reference; do not paste into the listing |
| `cinestream-upload-certificate.pem` | Public upload certificate reference; not a private key |
| `VERIFICATION.md` | Build, signing, package, and native inspection results |
| `ARTWORK.md` | Screenshot source, generation prompts, and alt text |

The screenshot artwork replaces listing screenshots separately. Updating the AAB does not change the Play listing images automatically. Review each image in Console before saving. The app UI shown should match the release, and any source-video imagery must be authorized for public display.

## Release identity and signing

- Version name: `9.6.3`
- Version code: `95`
- Package: `com.exor.cinestream`
- Verified bundle upload certificate SHA1: `65:F6:4C:32:53:06:35:FD:31:9B:93:2B:F9:AC:2A:31:5A:7C:FF:E5`
- Signed AAB size: `19,409,023 bytes` (19.4 MB); this includes both ARM variants and is not an individual device's download size.

Before uploading, compare that fingerprint with the **Upload key certificate** in this app's Play app signing settings. An app signing certificate with another fingerprint is normal if Google signs the installed app with a separate key. If the upload certificate is still different or its reset is pending, wait until the approved key becomes active. No private signing key, keystore, or password is included in this handover.

Version code 95 must exceed the highest version already used for this app. If Console has already received 95 or higher, request a bundle with a higher version code rather than uploading it to another app.

## Operator steps

1. Open the existing CineStream app in Play Console and confirm its package is `com.exor.cinestream`.
2. Confirm the active upload certificate and version-code availability as described above.
3. Create the next release in the appropriate testing or production track and upload `CineStream-9.6.3-play.aab`.
4. Name the release `9.6.3 - Clearer video access and library actions` and paste `whats-new-en-US.txt` into the English (United States) release notes.
5. Replace the English listing's phone screenshots with the four numbered images in order. Use `store-listing-en-US.txt` only if updating listing copy. Keep existing approved declarations unless the app's behavior or Console requires an update.
6. Check Console's bundle processing, declarations, warnings, screenshot previews, and release summary; address any reported blockers before submitting the update for review.

## What changed

- Android requests video permission automatically on the first opening when access has not been granted.
- Denial shows a clear explanation and **Allow access**; **Open settings** is used when Android prevents another permission prompt.
- Android 14+ selected-video access shows only allowed videos and offers **Manage access**.
- The selection bar now labels **Rename**, **Share**, **Info**, and **Delete**. Rename and Info require one selected video; Share and Delete continue to support multiple videos.
- The Play listing has a refreshed phone presentation using the app's screens.

Audio, subtitles, picture-in-picture, gestures, codecs, and video compatibility were not changed for this update. No new device or playback tests are claimed for this release preparation; manual validation is performed by the user. A completed build or signature check does not mean Play has approved the release.

## Listing links and support

- App: https://play.google.com/store/apps/details?id=com.exor.cinestream
- Repository: https://github.com/exor-26/CineStream
- Privacy policy: https://github.com/exor-26/CineStream/blob/main/PRIVACY_POLICY.md
- Support: adityakumar3575@gmail.com
