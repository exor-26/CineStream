package com.example.cinestream;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ProgressiveCompatibilityPolicyTest {
    @Test
    public void firstAndLaterSegmentWindowsAreDeterministic() {
        ProgressiveCompatibilityPolicy.SegmentWindow first =
                ProgressiveCompatibilityPolicy.segmentWindow(0, 60_000L);
        ProgressiveCompatibilityPolicy.SegmentWindow second =
                ProgressiveCompatibilityPolicy.segmentWindow(1, 60_000L);
        ProgressiveCompatibilityPolicy.SegmentWindow third =
                ProgressiveCompatibilityPolicy.segmentWindow(2, 60_000L);

        assertEquals(0L, first.startMs);
        assertEquals(3_000L, first.endMs);
        assertEquals(3_000L, second.startMs);
        assertEquals(8_000L, second.endMs);
        assertEquals(8_000L, third.startMs);
        assertEquals(13_000L, third.endMs);
        assertEquals(2, ProgressiveCompatibilityPolicy.segmentIndexForPosition(8_500L));
    }

    @Test
    public void finalShortSegmentStopsAtSourceDuration() {
        ProgressiveCompatibilityPolicy.SegmentWindow finalWindow =
                ProgressiveCompatibilityPolicy.segmentWindow(2, 9_250L);
        assertEquals(8_000L, finalWindow.startMs);
        assertEquals(9_250L, finalWindow.endMs);
        assertEquals(1_250L, finalWindow.durationMs());
    }

    @Test
    public void playbackWaitsForTwoSegmentsAndEightSeconds() {
        assertFalse(ProgressiveCompatibilityPolicy.hasInitialBuffer(1, 3_000L, false));
        assertFalse(ProgressiveCompatibilityPolicy.hasInitialBuffer(2, 7_999L, false));
        assertTrue(ProgressiveCompatibilityPolicy.hasInitialBuffer(2, 8_000L, false));
        assertTrue(ProgressiveCompatibilityPolicy.hasInitialBuffer(1, 2_000L, true));
    }

    @Test
    public void slowGenerationDropsTiersAndMeasures480BeforeFailure() {
        ProgressiveCompatibilityPolicy.Adaptation verySlow =
                ProgressiveCompatibilityPolicy.adapt(
                        VideoResourceGovernor.Tier.P1080_60,
                        VideoResourceGovernor.Tier.P1080_60,
                        0.45d,
                        0,
                        true
                );
        assertEquals(VideoResourceGovernor.Tier.P720_30, verySlow.tier);
        assertTrue(verySlow.sustainable);

        ProgressiveCompatibilityPolicy.Adaptation first480 =
                ProgressiveCompatibilityPolicy.adapt(
                        VideoResourceGovernor.Tier.P480_24,
                        VideoResourceGovernor.Tier.P1080_60,
                        0.60d,
                        0,
                        false
                );
        assertTrue(first480.sustainable);

        ProgressiveCompatibilityPolicy.Adaptation measured480 =
                ProgressiveCompatibilityPolicy.adapt(
                        VideoResourceGovernor.Tier.P480_24,
                        VideoResourceGovernor.Tier.P1080_60,
                        0.60d,
                        0,
                        true
                );
        assertFalse(measured480.sustainable);
    }

    @Test
    public void qualityRecoveryNeverExceedsGovernorCeiling() {
        ProgressiveCompatibilityPolicy.Adaptation firstFast =
                ProgressiveCompatibilityPolicy.adapt(
                        VideoResourceGovernor.Tier.P480_24,
                        VideoResourceGovernor.Tier.P720_30,
                        1.6d,
                        0,
                        true
                );
        ProgressiveCompatibilityPolicy.Adaptation secondFast =
                ProgressiveCompatibilityPolicy.adapt(
                        firstFast.tier,
                        VideoResourceGovernor.Tier.P720_30,
                        1.6d,
                        firstFast.consecutiveFastSegments,
                        true
                );
        assertEquals(VideoResourceGovernor.Tier.P720_30, secondFast.tier);

        ProgressiveCompatibilityPolicy.Adaptation stillBounded =
                ProgressiveCompatibilityPolicy.adapt(
                        secondFast.tier,
                        VideoResourceGovernor.Tier.P720_30,
                        2.0d,
                        2,
                        true
                );
        assertEquals(VideoResourceGovernor.Tier.P720_30, stillBounded.tier);
    }

    @Test
    public void cacheNamesSeparateCompletedAndIncompleteOutputs() {
        String source = ProgressiveCompatibilityCache.stableSourceIdentity(
                "content://video/42|123456|999"
        );
        assertEquals(source, ProgressiveCompatibilityCache.stableSourceIdentity(
                "content://video/42|123456|999"
        ));
        assertNotEquals(source, ProgressiveCompatibilityCache.stableSourceIdentity(
                "content://video/42|123457|999"
        ));

        File completed = ProgressiveCompatibilityCache.completedSegment(
                new File("cache"),
                source,
                ProgressiveCompatibilityPolicy.segmentWindow(1, 60_000L),
                new CompatibilityVideoPolicy.Target(1280, 720, 30f)
        );
        File incomplete = ProgressiveCompatibilityCache.incompleteSegment(completed);
        assertTrue(completed.getName().endsWith(".mp4"));
        assertTrue(incomplete.getName().endsWith(".mp4.part"));
        assertTrue(ProgressiveCompatibilityCache.isCompletedSegment(completed));
        assertFalse(ProgressiveCompatibilityCache.isCompletedSegment(incomplete));
        assertTrue(ProgressiveCompatibilityCache.isIncompleteSegment(incomplete));
    }
}
