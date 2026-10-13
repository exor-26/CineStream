package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PlayerControlsLayoutPolicyTest {
    @Test public void matureEdgeSpacingSeparatesPortraitLockFromPlaybackRow() {
        assertEquals(-72, PlayerControlsLayoutPolicy.lockOffsetY(420, 933, 66, 354, 16, 64, 64, 48, 16));
    }
    @Test public void roomyPortraitKeepsLockOnTheSameCenterLine() {
        assertEquals(0, PlayerControlsLayoutPolicy.lockOffsetY(420, 933, 66, 354, 8, 56, 64, 48, 8));
    }
    @Test public void narrowPortraitSeparatesLockFromPreviousButton() {
        assertEquals(-64, PlayerControlsLayoutPolicy.lockOffsetY(360, 800, 36, 324, 8, 56, 64, 48, 8));
    }
    @Test public void rtlPortraitSeparatesLockFromNextButton() {
        assertEquals(-64, PlayerControlsLayoutPolicy.lockOffsetY(360, 800, 36, 324, 304, 352, 64, 48, 8));
    }
    @Test public void landscapeKeepsTheLeftCenterPlacement() {
        assertEquals(0, PlayerControlsLayoutPolicy.lockOffsetY(933, 420, 322, 610, 50, 98, 64, 48, 8));
    }
    @Test public void geometryScalesWithDensityWithoutChangingBehavior() {
        assertEquals(-192, PlayerControlsLayoutPolicy.lockOffsetY(1080, 2400, 108, 972, 24, 168, 192, 144, 24));
    }
}
