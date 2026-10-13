# CineStream 9.7.0 screen-adaptation map

Scope: Android phones and tablets; sizing uses the current activity window in dp, never an
OEM, model, chipset, or physical display-resolution table. Normal-phone glass styling,
gesture routing, original audio, media timelines, decoder recovery, and PiP remain intact.

| Window or condition | Handling |
| --- | --- |
| Normal phone, portrait or landscape | Existing 48dp buttons, 64dp Play/Pause, 8dp transport gaps, and 16dp screen-edge spacing |
| Narrow phone or resized window | Reduce gaps first, then use 48dp Play/Pause; exceptionally narrow transport viewports can scroll horizontally instead of shrinking touch targets |
| Short landscape window | 8dp edges; center controls are bounded between the toolbar content and actual dock bounds |
| Tablet with at least 600dp width and 480dp height | 24dp edges; floating dock caps at 960dp; dialogs cap at 640dp |
| Long media-information dialog in a short window | Weighted information scroll area leaves space for its title and Close action |
| Large text or long timestamps | Time pill reserves Settings and an 8dp gap; complete timestamps stack if the horizontal pair cannot fit, and the footer grows |
| Cutout or system navigation | Top toolbar accounts for top cutouts; bottom dock accounts for visible navigation; feedback reserves stable status/cutout insets |
| Any of the seven feedback types | Brightness, volume/boost, temporary 2x, lock/unlock, pinch percentage, seek preview, and crop preview hide top/center/bottom together; the feedback lane does not depend on controller visibility |
| Lock | Settings -> Lock; unlock remains the bottom-center slider |
| Window resize or orientation change | Re-measure from current bounds, recompute center placement, dismiss the app settings popup if its anchor geometry changes; do not recreate the playback pipeline |
| PiP | Existing Android system PiP controls and lifecycle; custom full-screen controls remain suppressed |

## Local verification targets

Geometry tests cover 240/280/320/360/411dp narrow/normal widths, short 240/280dp landscape
heights, 600/800/1280dp tablet widths, and 1x/2x/3x density conversions. Layout contracts
check native Media3 IDs, touch-target sizing, the two-entry Settings menu, font-safe time
layout, whole-controller feedback hiding, and unchanged explorer/playback theme separation.

Normal windows retain the approved layout. Exceptionally tiny windows cannot physically show
every large-text control simultaneously; the narrow transport can scroll, and modal lists
remain scrollable. Dedicated fold-hinge-aware, two-pane placement is not implemented or
claimed here; opening/folding that changes activity bounds uses the same resize policy.

Host geometry/layout tests and release builds are not screenshots or gesture tests on every
physical screen. Before publication, manually check phone/tablet portrait and landscape,
200% font size, narrow resized windows, cutouts/navigation, Settings -> Speed/Lock, all seven
feedback types, unlock, and PiP. Existing 9.6.3 artifacts remain historical; a fresh 9.7.0
Play handover requires a newly built/signed bundle and matching reviewed screenshots.
