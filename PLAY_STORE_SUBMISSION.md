# CineStream Google Play Submission

Prepared for version **9.6.1** (`versionCode 93`) on September 7, 2026.

## App identity

- App name: `CineStream`
- Default language: `English (United States) — en-US`
- App or game: `App`
- Free or paid: `Free`
- Package name: `com.example.cinestream`
- Category: `Video Players & Editors`
- Developer name: `Aditya Singh`
- Support email: `adityakumar3575@gmail.com`
- Website: `https://github.com/exor-26/CineStream`
- Privacy policy: `https://github.com/exor-26/CineStream/blob/main/PRIVACY_POLICY.md`

## Store listing

### Short description

Private local video playback with broad codec recovery and smooth controls.

### Full description

CineStream is a private, local-first video player built for reliable playback across modern Android devices.

Browse videos stored on your device, search by title, switch between video and folder views, sort your library, and resume from where you stopped. CineStream keeps playback responsive with direct controls, screen lock, pinch zoom, crop modes, gesture seeking, brightness and volume gestures, and temporary 2x speed.

For challenging media, CineStream starts with Android hardware decoding for efficiency and quality. When the platform cannot play a video reliably, the app can use its bundled on-device software decoder and governed H.264 compatibility recovery. Original audio tracks, subtitles, metadata, and synchronization remain part of the playback experience.

Highlights:
- Local video and folder library
- Hardware-first playback
- Broad on-device codec recovery
- Multiple audio-track and subtitle selection
- Picture-in-picture with playlist controls
- Playback-position history
- Pinch zoom, crop, seek, brightness, and volume gestures
- Scoped-storage-safe share, rename, and delete actions
- No account, advertising, analytics, or tracking
- No internet permission

Your videos stay on your device. CineStream performs playback and compatibility work locally and does not upload your media.

### Release name

9.6.1 - Google Play launch

### Release notes

Initial Google Play release with hardware-first local playback, on-device codec recovery, audio and subtitle tracks, gesture controls, playback resume, and lifecycle-safe picture-in-picture. Targets Android 16/API 36 and includes a public in-app privacy policy link.

## App content declarations

- Ads: `No, the app does not contain ads.`
- App access: `All functionality is available without login, membership, location, or special credentials.`
- Target audience: `Ages 13 and over`; do not select child age groups unless the product is intentionally redesigned for children.
- News app: `No.`
- Government app: `No.`
- Health app: `No.`
- Financial features: `No.`
- Account creation: `No`; account deletion requirement is not applicable.
- User-generated content: CineStream does not host or distribute user content. It only plays media already available to the user through Android storage access.
- Content rating: Answer `No` to app-provided violence, sexual content, language, drugs, gambling, and online interaction. State that the app plays user-owned local media and does not provide a media catalog.

## Data safety answers

- Does the app collect or share required user-data types? `No.`
- Is all user data encrypted in transit? `Not applicable; CineStream does not transmit user data and has no internet permission.`
- Can users request data deletion? `Not applicable for developer-held data. Local playback history and cache can be removed using Android Clear storage or uninstall.`
- Data processing: local video access, metadata, thumbnails, playback position, preferences, and compatibility-cache files remain on-device.
- Sharing exception: a video leaves CineStream only when the user explicitly selects Share and chooses a recipient through Android's system Sharesheet.

## Photo and video permission declaration

### Core functionality

CineStream is a dedicated local video library and player. Broad video access is required to continuously discover and display the user's device video library, generate thumbnails and metadata, build folders and playlists, resume playback, and provide user-initiated playback, share, rename, and delete operations. A one-time system picker cannot provide the app's core device-library experience.

### Reviewer instructions

1. Launch CineStream on a device containing multiple local videos.
2. Grant video access when Android requests it.
3. Confirm the library displays videos and folders with thumbnails, metadata, search, and sorting.
4. Open a video and verify playback, audio/subtitle controls, resume, and picture-in-picture.
5. Long-press a video to view the user-initiated share, rename, details, and delete actions.

If Play Console requests a demonstration video for the broad-media declaration, record these five steps on a physical device and provide an accessible YouTube unlisted or cloud-storage link.

## Graphic assets

- Play icon: `playstore/graphics/play-icon-512x512.png` — verified 512 x 512 RGBA PNG.
- Feature graphic: `playstore/graphics/feature-graphic-1024x500.png` — verified 1024 x 500 RGB PNG.
- Phone screenshots: at least two real screenshots are required. Recommended set: library view, folder view, player controls, audio/subtitle sheet, and picture-in-picture.
- Screenshot format: JPEG or 24-bit PNG without alpha, each side 320-3840 px, and no side more than twice the other side.
- Preview video: optional. Do not upload copyrighted sample-video footage.

## Release artifact

Upload `playstore/release/CineStream-9.6.1-play.aab` to Play Console. The bundle includes both supported native ABIs. Google Play generates a reduced device-specific APK, so arm64 devices receive only the arm64-v8a native library rather than the 32-bit library.

- Signed AAB size: `19,407,771 bytes` (18.51 MiB)
- SHA-256: `0B8932F5A6FA88815F6760DA942C8580D948F595D3F99E42193872684329A03F`
- Simulated Android 16 arm64 delivery: `15,383,201 bytes` across generated configuration APKs
- Bundle contents: `arm64-v8a` and `armeabi-v7a` only; no x86 payload
- Manifest: min SDK 24, target SDK 36, version code 93
- Native compatibility: every arm64 shared library uses 16 KiB ELF LOAD alignment
- Bundle validation and signature verification: passed

Rebuild it with `.\gradlew.bat testDebugUnitTest bundleRelease -PplayBundle=true`. The property disables APK-output splitting only for the bundle task; normal release APK builds remain ABI-specific.

Enable Play App Signing and treat the existing CineStream key as the upload key. Never upload the keystore or passwords to Play Console fields, source control, support tickets, or the store listing.

## Remaining manual Play Console items

- Complete developer identity and package-name verification if Play Console requests it.
- Accept Play App Signing terms.
- Upload at least two genuine phone screenshots.
- Confirm distribution countries/regions and whether the app will be free.
- Complete the content-rating questionnaire using the answers above.
- Submit the Photo and Video Permissions declaration after the bundle is uploaded.
- Start with Internal testing, review Play's automated pre-launch report, then promote when clean.
- If the publisher is a personal developer account created after November 13, 2023, run the required closed test with at least 12 testers opted in continuously for 14 days, then apply for production access.
