package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenAdaptationPolicyTest {
    @Test public void normalPhonesKeepApprovedDimensions() {
        for (int width : new int[]{320, 360, 411}) {
            int viewport = PlayerControlsLayoutPolicy.transportViewport(width);
            assertEquals(16, PlayerControlsLayoutPolicy.edgeGap(width, 800));
            assertEquals(64, PlayerControlsLayoutPolicy.playSize(viewport, 800));
            assertEquals(8, PlayerControlsLayoutPolicy.transportGap(viewport, 64));
        }
    }

    @Test public void narrowTransportKeepsEveryTouchTargetAndBoundsItsViewport() {
        for (int width : new int[]{240, 280, 320, 360, 411, 600, 800, 1280}) {
            int viewport = PlayerControlsLayoutPolicy.transportViewport(width);
            int play = PlayerControlsLayoutPolicy.playSize(viewport, 640);
            int gap = PlayerControlsLayoutPolicy.transportGap(viewport, play);
            assertTrue(viewport < width);
            assertTrue(play >= 48);
            assertTrue(gap >= 0 && gap <= 8);
            int row = 4 * 48 + play + 4 * gap;
            if (viewport >= 240) assertTrue(row <= viewport);
            else assertEquals("Only an exceptionally narrow viewport needs horizontal scrolling", 240, row);
        }
    }

    @Test public void shortLandscapeKeepsTopCenterAndDockSeparated() {
        for (int height : new int[]{240, 280, 320, 360, 480}) {
            int edge = PlayerControlsLayoutPolicy.edgeGap(640, height);
            int play = PlayerControlsLayoutPolicy.playSize(PlayerControlsLayoutPolicy.transportViewport(640), height);
            int toolbarContentBottom = edge + 48;
            int dockTop = height - edge - 96;
            int centerTop = PlayerControlsLayoutPolicy.centerTop(height, play, toolbarContentBottom, dockTop, 8);
            assertTrue(centerTop >= toolbarContentBottom + 8);
            assertTrue(centerTop + play + 8 <= dockTop);
        }
    }

    @Test public void tallTimePillStillFitsCompactLandscapeWithoutSettingsOverlap() {
        int height = 240, edge = PlayerControlsLayoutPolicy.edgeGap(640, 240);
        int centerTop = PlayerControlsLayoutPolicy.centerTop(height, 48, edge + 48, height - edge - 108, 8);
        assertTrue(centerTop >= edge + 48 + 8);
        assertTrue(centerTop + 48 + 8 <= height - edge - 108);
    }

    @Test public void tabletDockAndDialogsHaveReadableMaximumWidths() {
        for (int width : new int[]{600, 800, 1280}) {
            int edge = PlayerControlsLayoutPolicy.edgeGap(width, 800);
            assertEquals(24, edge);
            assertTrue(PlayerControlsLayoutPolicy.dockWidth(width, edge) <= 960);
            assertTrue(PlayerControlsLayoutPolicy.dockWidth(width, edge) <= width - 2 * edge);
            assertTrue(PlayerControlsLayoutPolicy.dialogWidth(width) <= 640);
        }
        assertEquals(960, PlayerControlsLayoutPolicy.dockWidth(1280, 24));
        assertEquals(640, PlayerControlsLayoutPolicy.dialogWidth(1280));
    }

    @Test public void narrowTabletWindowUsesItsCurrentBoundsNotItsDeviceCategory() {
        assertEquals(24, PlayerControlsLayoutPolicy.edgeGap(800, 1000));
        assertEquals(8, PlayerControlsLayoutPolicy.edgeGap(280, 640));
        assertEquals(264, PlayerControlsLayoutPolicy.dockWidth(280, 8));
    }

    @Test public void densityConversionPreservesGeometry() {
        for (float density : new float[]{1f, 1.25f, 1.5f, 2f, 2.75f, 3f}) {
            int viewportDp = PlayerControlsLayoutPolicy.transportViewport(320);
            int viewportPx = Math.round(viewportDp * density);
            assertEquals(viewportDp, Math.round(viewportPx / density));
            int top = PlayerControlsLayoutPolicy.centerTop(Math.round(320 * density), Math.round(64 * density),
                    Math.round(64 * density), Math.round(208 * density), Math.round(8 * density));
            assertEquals(128, Math.round(top / density));
        }
    }
}
