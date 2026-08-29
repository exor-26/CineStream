package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerGestureMathTest {

    @Test
    public void zoomDefaultsToOneHundredPercent() {
        assertEquals(100, PlayerGestureMath.zoomPercentage(1f));
        assertEquals(115, PlayerGestureMath.zoomPercentage(1.15f));
    }

    @Test
    public void zoomHasModestOutFloor() {
        assertEquals(
                PlayerGestureMath.MIN_ZOOM,
                PlayerGestureMath.clampZoom(0.4f, 1000, 600, 1000, 600),
                0.0001f
        );
    }

    @Test
    public void zoomCeilingRespondsToSurfaceCoverage() {
        float normal = PlayerGestureMath.maxZoom(1000, 600, 1000, 600);
        float alreadyOversized = PlayerGestureMath.maxZoom(2000, 1200, 1000, 600);
        assertTrue(normal > alreadyOversized);
        assertTrue(alreadyOversized >= 1.25f);
    }

    @Test
    public void seekMapsDistanceAndClampsAtEnds() {
        long duration = 600_000L;
        assertEquals(
                172_000L,
                PlayerGestureMath.mapSeekTarget(100_000L, duration, 1000f, 1000f)
        );
        assertEquals(
                0L,
                PlayerGestureMath.mapSeekTarget(10_000L, duration, -1000f, 1000f)
        );
        assertEquals(
                duration,
                PlayerGestureMath.mapSeekTarget(590_000L, duration, 1000f, 1000f)
        );
    }

    @Test
    public void longMediaSeekRangeIsCapped() {
        assertEquals(180_000L, PlayerGestureMath.seekRangeMs(7_200_000L));
    }

    @Test
    public void logicalPositionIncludesPrecedingWindows() {
        long[] durations = {30_000L, 30_000L, 20_000L};
        assertEquals(42_000L, PlayerGestureMath.logicalPosition(durations, 1, 12_000L));
        assertEquals(80_000L, PlayerGestureMath.logicalDuration(durations));
    }

    @Test
    public void logicalSeekResolvesSegmentWindow() {
        long[] durations = {30_000L, 30_000L, 20_000L};
        PlayerGestureMath.SeekWindow target =
                PlayerGestureMath.resolveSeekWindow(durations, 65_000L);
        assertEquals(2, target.windowOffset);
        assertEquals(5_000L, target.positionInWindowMs);
    }

    @Test
    public void logicalSeekAllowsExactEndOfLastWindow() {
        long[] durations = {30_000L, 20_000L};
        PlayerGestureMath.SeekWindow target =
                PlayerGestureMath.resolveSeekWindow(durations, 50_000L);
        assertEquals(1, target.windowOffset);
        assertEquals(20_000L, target.positionInWindowMs);
    }

    @Test
    public void timestampFormattingCoversHours() {
        assertEquals("1:05", PlayerGestureMath.formatTimestamp(65_000L));
        assertEquals("1:01:01", PlayerGestureMath.formatTimestamp(3_661_000L));
    }
}
