package com.exor.cinestream;

/** Pure auto-hide policy, independent of player/decoder state. */
final class PlayerControlsVisibilityState {
    boolean visible;
    boolean scrubbing;
    boolean pointerDown;
    boolean focused = true;
    boolean suppressed;
    boolean feedbackActive;
    boolean menuOpen;

    boolean canShow() { return !suppressed && !feedbackActive; }

    boolean canAutoHide() {
        return visible && focused && !scrubbing && !pointerDown && canShow() && !menuOpen;
    }
}
