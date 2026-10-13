# CineStream 9.7.0 Play update handover

Prepared October 13, 2026 for the existing app `com.exor.cinestream`.

Send **CineStream-9.7.0-Play-Update.zip** to the Play Console operator. It replaces the
previous handover for this update; the older 9.6.3 archive is retained as a backup.

| File | Use |
| --- | --- |
| `CineStream-9.7.0-play.aab` | Upload as the new app bundle to the existing app |
| `whats-new-en-US.txt` | Paste into English (United States) release notes |
| `store-listing-en-US.txt` | Optional updated listing copy |
| `screenshots/01-library.png` | Supplied Explorer.png; replace phone screenshot 1 |
| `screenshots/02-tracks.png` | Existing track-selection artwork, unchanged |
| `screenshots/03-controls.png` | Supplied Player.png; replace phone screenshot 3 |
| `screenshots/04-privacy.png` | Existing privacy artwork, unchanged |
| `graphics/` | Existing Play icon and feature graphic, unchanged |
| `cinestream-upload-certificate.pem` | Public certificate reference only |
| `SHA256SUMS.txt` | Check file integrity; not listing copy |
| `VERIFICATION.md`, `ARTWORK.md`, `SCREEN_ADAPTATION.md` | Verification and review boundaries |

## Upload gates — check these before creating the release

- Package: **com.exor.cinestream**. Use the existing Play app, not a new app record.
- Version name: **9.7.0**. Version code: **96**.
- Code 96 must be higher than every version code already used for this app.
- The active **Upload key certificate** must have SHA1
  `65:F6:4C:32:53:06:35:FD:31:9B:93:2B:F9:AC:2A:31:5A:7C:FF:E5`.
- Google's separate **App signing key certificate** can differ. Compare the upload certificate,
  not the app-signing certificate. If the upload reset is pending or Console still expects
  another upload key, wait for the approved reset to become active. Renaming the AAB cannot fix it.
- The ZIP contains no private signing key, keystore, password, or APK.

## Operator flow

1. Check the package, active upload certificate, and available version code above.
2. Create the next release in the intended testing/production track and upload the AAB.
3. Release name: **9.7.0 - Glass player controls and adaptive layouts**.
4. Paste `whats-new-en-US.txt` into the English (United States) release notes.
5. Replace the phone screenshots using the numbered files. Screenshots are separate listing
   assets: uploading the AAB does not update them. The owner supplied the new library/player
   artwork; the tracks/privacy files are retained from the previous package.
6. Use the optional listing copy if wanted. Retain correct existing account/declaration data;
   do not change publisher identity merely to match an in-app developer credit.
7. Review Console processing, permission declarations, warnings, pre-launch results, and
   screenshot previews before submitting. No visual screenshot review was performed here.

## What is in this update

- Dark themed glass player controls, animated Play/Pause, a separate seekbar and time pill.
- Settings offers Speed and Lock; Speed opens Media3's existing picker.
- Bottom-center swipe-to-unlock and a live pinch percentage.
- Brightness, volume/boost, temporary 2x, lock/unlock, zoom, seek and crop feedback hide the
  whole controller together and use a stable upper lane.
- Window-based sizing for phones/tablets, short landscape and large text.
- Library permission recovery, labeled multi-selection actions, metadata/thumbnail caching,
  and bounded MKV header discovery from the current source.
- Original audio/subtitle sources, PiP, and hardware-first recovery remain in the architecture.

Source/bundle checks do not prove playback on every device, every Dolby Vision color path,
fold-hinge behavior, or acceptance by Play. No new physical playback or screenshot review was
performed for this handover. The prior 9.7.0 arm64 APK was installed and launched for the
owner's manual testing; that is not an end-to-end test of this AAB through Google Play.

App: https://play.google.com/store/apps/details?id=com.exor.cinestream

Repository: https://github.com/exor-26/CineStream

Privacy: https://github.com/exor-26/CineStream/blob/main/PRIVACY_POLICY.md

Support: adityakumar3575@gmail.com
