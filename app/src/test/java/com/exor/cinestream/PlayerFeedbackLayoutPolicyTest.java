package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerFeedbackLayoutPolicyTest {
    @Test public void portraitFeedbackUsesUpperLaneInsteadOfCenter() {
        assertEquals(100, PlayerFeedbackLayoutPolicy.top(1000, 74, 0, 8));
        assertEquals(100, PlayerFeedbackLayoutPolicy.top(1000, 40, 0, 8));
    }

    @Test public void shortLandscapeUsesTheSameLaneForCompactAndTallFeedback() {
        assertEquals(40, PlayerFeedbackLayoutPolicy.top(400, 40, 0, 8));
        assertEquals(40, PlayerFeedbackLayoutPolicy.top(400, 74, 0, 8));
        assertEquals(40, PlayerFeedbackLayoutPolicy.top(400, 112, 0, 8));
    }

    @Test public void placementHasNoToolbarOrControllerVisibilityDependency() {
        // Those states deliberately are not policy inputs: showing them cannot move feedback.
        assertEquals(40, PlayerFeedbackLayoutPolicy.top(400, 40, 0, 8));
    }

    @Test public void feedbackRespectsTopCutout() {
        assertEquals(88, PlayerFeedbackLayoutPolicy.top(600, 40, 80, 8));
    }

    @Test public void tallCropPreviewRemainsAtTheSameUpperAnchor() {
        assertEquals(36, PlayerFeedbackLayoutPolicy.top(360, 112, 0, 8));
    }

    @Test public void exceptionallyTallFeedbackClampsOnlyToWindowBounds() {
        int top = PlayerFeedbackLayoutPolicy.top(160, 140, 0, 8);
        assertEquals(12, top);
        assertTrue(top + 140 + 8 <= 160);
    }

    @Test public void shortWindowStillUsesTheUpperLane() {
        assertEquals(16, PlayerFeedbackLayoutPolicy.top(160, 112, 0, 8));
    }

    @Test public void geometryScalesWithDensityWithoutDeviceConditions() {
        assertEquals(120, PlayerFeedbackLayoutPolicy.top(1200, 120, 0, 24));
    }
}
