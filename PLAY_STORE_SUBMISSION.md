# CineStream Google Play Submission

Current local target: **9.7.0** (`versionCode 96`). It is not published. Screen adaptation is mapped in [docs/SCREEN_ADAPTATION.md](docs/SCREEN_ADAPTATION.md). A new signed bundle and current screenshots must be prepared before handing over this version; do not rename or upload an old 9.6.3 bundle as 9.7.0.

The following submission package is the historical **9.6.3** (`versionCode 95`) preparation from October 3, 2026, for the existing `com.exor.cinestream` listing. Its filenames, signatures, artwork, and verification records intentionally retain their original version.

## App identity

- App name: `CineStream Local Video Player`
- Default language: `English (United States) — en-US`
- App or game: `App`
- Free or paid: `Free`
- Package name: `com.exor.cinestream`
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

9.6.3 - Clearer video access and library actions

### Release notes

Video access is clearer: Android asks on first launch, with an explanation and Allow access if you deny. Open settings is offered when needed. On Android 14+, manage the videos you allow. Library actions now include Rename, Share, Info, and Delete labels. Existing playback, audio, subtitles, and picture-in-picture remain available.

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
2. Grant video access when Android requests it on first launch. If denied, use Allow access on the explanation screen; Open settings is offered if Android blocks further prompts.
3. Confirm the library displays videos and folders with thumbnails, metadata, search, and sorting.
4. Open a video and verify playback, audio/subtitle controls, resume, and picture-in-picture.
5. Long-press a video to view labeled Rename, Share, Info, and Delete actions. Rename and Info require one selected video; Share and Delete support multiple selections.
6. On Android 14+, optionally choose selected-video access and use Manage access to adjust the available videos.

If Play Console requests a demonstration video for the broad-media declaration, record the relevant steps on a physical device and provide an accessible YouTube unlisted or cloud-storage link.

## Graphic assets

- Play icon: `playstore/updates/9.6.3/graphics/play-icon-512x512.png` — 512 x 512 PNG.
- Feature graphic: `playstore/updates/9.6.3/graphics/feature-graphic-1024x500.png` — 1024 x 500 PNG.
- Updated phone screenshots: `playstore/updates/9.6.3/screenshots/01-library.png`, `02-tracks.png`, `03-controls.png`, and `04-privacy.png`. These use real app screens inside presentation artwork. Upload them separately to the listing, in this order, after reviewing their previews.
- Screenshot format: JPEG or 24-bit PNG without alpha, each side 320-3840 px, and no side more than twice the other side.
- Preview video: optional. Do not upload copyrighted sample-video footage.

## Release artifact

Upload `playstore/updates/9.6.3/CineStream-9.6.3-play.aab` as an update to the existing Play Console app whose package is `com.exor.cinestream`. The package must match that app record. Confirm version code 95 is higher than every version code already uploaded for this app. Google Play generates device-specific APKs from the bundle; phone screenshots are separate listing assets and are not embedded in the AAB.

- Package: `com.exor.cinestream`
- Version: `9.6.3` / `95`
- Release files, certificate details, and handover steps: `playstore/updates/9.6.3/HANDOVER.md`
- Exact release sizes and hashes: accompanying `SHA256SUMS.txt` in the handover package
- This release preparation does not include new physical-device tests; the user handles manual checks.

Build with `.\gradlew.bat bundleRelease -PplayBundle=true`, then sign the output with the existing upload key using `jarsigner`. The property disables APK-output splitting only for the bundle task; normal release APK builds remain ABI-specific. This handover includes the signed bundle, not the unsigned Gradle output.

Confirm the **Upload key certificate** shown under Play app signing matches the bundle certificate (`SHA1: 65:F6:4C:32:53:06:35:FD:31:9B:93:2B:F9:AC:2A:31:5A:7C:FF:E5`). The **App signing key certificate** may legitimately differ; Google uses that key to sign installed Play downloads. Do not assume an earlier upload-key reset has completed. If Console still expects another upload certificate, finish the authorized reset and wait for its activation time before uploading. Never send the keystore or passwords with the update handover.

## Remaining manual Play Console items

- Complete developer identity and package-name verification if Play Console requests it.
- Confirm the existing app's active upload certificate and available version code.
- Replace the phone screenshot set with the reviewed 9.6.3 artwork; retain the existing icon and feature graphic unless deliberately changing them.
- Confirm distribution countries/regions and whether the app will be free.
- Complete the content-rating questionnaire using the answers above.
- Submit the Photo and Video Permissions declaration after the bundle is uploaded.
- Start with Internal testing, review Play's automated pre-launch report, then promote when clean.
- Follow any testing, account-verification, declaration, or review requirements shown by the publisher's Play Console. Preparing a signed bundle does not guarantee Play approval.
