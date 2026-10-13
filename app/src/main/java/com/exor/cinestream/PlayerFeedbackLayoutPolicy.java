package com.exor.cinestream;

/** Height-aware upper feedback lane; inputs are geometry, not device identities. */
final class PlayerFeedbackLayoutPolicy {
    private PlayerFeedbackLayoutPolicy() {}

    static int top(int height, int overlayHeight, int safeTop, int gap) {
        int minimum = Math.max(0, safeTop) + gap;
        int preferred = Math.max(minimum, height / 10);
        // Clamp only for a panel too tall for this window, never for player controls.
        return Math.max(minimum, Math.min(preferred, height - gap - overlayHeight));
    }
}
