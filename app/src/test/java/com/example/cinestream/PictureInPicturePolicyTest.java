package com.example.cinestream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

public class PictureInPicturePolicyTest {

    @Test
    public void entersOnlyForActiveSupportedPlayback() {
        assertTrue(PictureInPicturePolicy.shouldEnter(
                26, true, Player.STATE_READY, true));
        assertTrue(PictureInPicturePolicy.shouldEnter(
                35, true, Player.STATE_BUFFERING, true));

        assertFalse(PictureInPicturePolicy.shouldEnter(
                25, true, Player.STATE_READY, true));
        assertFalse(PictureInPicturePolicy.shouldEnter(
                35, false, Player.STATE_READY, true));
        assertFalse(PictureInPicturePolicy.shouldEnter(
                35, true, Player.STATE_ENDED, true));
        assertFalse(PictureInPicturePolicy.shouldEnter(
                35, true, Player.STATE_READY, false));
    }

    @Test
    public void preservesNormalVideoAspectRatioAndRotation() {
        assertArrayEquals(
                new int[]{16, 9},
                PictureInPicturePolicy.resolveAspectRatio(1920, 1080, 0, 1f)
        );
        assertArrayEquals(
                new int[]{9, 16},
                PictureInPicturePolicy.resolveAspectRatio(1920, 1080, 90, 1f)
        );
    }

    @Test
    public void clampsExtremeRatiosToAndroidPipBounds() {
        assertArrayEquals(
                new int[]{239, 100},
                PictureInPicturePolicy.resolveAspectRatio(8000, 1000, 0, 1f)
        );
        assertArrayEquals(
                new int[]{100, 239},
                PictureInPicturePolicy.resolveAspectRatio(1000, 8000, 0, 1f)
        );
    }

    @Test
    public void fallsBackWhenVideoDimensionsAreUnavailable() {
        assertArrayEquals(
                new int[]{16, 9},
                PictureInPicturePolicy.resolveAspectRatio(0, 0, 0, 1f)
        );
    }
}
