package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoResourceGovernorTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    public void healthyRuntimeKeepsHighestCeiling() {
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot(3 * GIB, 8 * GIB, false, 8, 0.25d, 0,
                        1920, 1080, 60f, 20_000_000L, 8, 2400, 1080, 120f, 1),
                new VideoResourceGovernor.Observation(6_000L, 5_900L, 350, 5)
        );

        assertEquals(VideoResourceGovernor.Tier.P1080_60, decision.ceiling);
        assertTrue(decision.directSoftwareSustainable);
    }

    @Test
    public void lowMemoryAndSevereThermalUseLowestCeiling() {
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot(300L * 1024L * 1024L, 4 * GIB, true, 8, 0.2d, 3,
                        3840, 2160, 60f, 50_000_000L, 10, 2400, 1080, 60f, -1),
                new VideoResourceGovernor.Observation(6_000L, 5_900L, 300, 2)
        );

        assertEquals(VideoResourceGovernor.Tier.P480_24, decision.ceiling);
        assertFalse(decision.directSoftwareSustainable);
    }

    @Test
    public void eightKTenBitSourceGetsConservativeCompatibilityCeiling() {
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot(3 * GIB, 8 * GIB, false, 8, 0.3d, 0,
                        7680, 4320, 60f, 100_000_000L, 10, 2400, 1080, 120f, -1),
                new VideoResourceGovernor.Observation(1_000L, 0L, 0, 0)
        );

        assertEquals(VideoResourceGovernor.Tier.P720_30, decision.ceiling);
        assertFalse(decision.observationMature);
        assertTrue(decision.directSoftwareSustainable);
    }

    @Test
    public void startupWindowDoesNotRejectSoftwarePrematurely() {
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot(2 * GIB, 6 * GIB, false, 6, Double.NaN, 0,
                        3840, 2160, 30f, 30_000_000L, 8, 1920, 1080, 60f, -1),
                new VideoResourceGovernor.Observation(4_999L, 500L, 10, 2)
        );

        assertFalse(decision.observationMature);
        assertTrue(decision.directSoftwareSustainable);
    }

    @Test
    public void matureSlowPlaybackHandsOff() {
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot(2 * GIB, 6 * GIB, false, 8, 0.8d, 1,
                        3840, 2160, 60f, 60_000_000L, 10, 1920, 1080, 60f, 1),
                new VideoResourceGovernor.Observation(6_000L, 3_200L, 140, 45)
        );

        assertTrue(decision.observationMature);
        assertFalse(decision.directSoftwareSustainable);
    }

    @Test
    public void codecMetadataCanRaiseBitDepthWithoutHdrFlag() {
        assertEquals(10, VideoResourceGovernor.estimateBitDepth("hvc1.2.4.L153.B0", false));
        assertEquals(10, VideoResourceGovernor.estimateBitDepth("av01.profile2.10", false));
        assertEquals(10, VideoResourceGovernor.estimateBitDepth(null, true));
        assertEquals(8, VideoResourceGovernor.estimateBitDepth("avc1.640028", false));
    }

    @Test
    public void compatibilityCeilingPreservesAspectRatio() {
        VideoResourceGovernor.Decision decision = new VideoResourceGovernor.Decision(
                VideoResourceGovernor.Tier.P720_30,
                false,
                true,
                "test"
        );
        CompatibilityVideoPolicy.Target target = decision.compatibilityCeiling(
                7680,
                4320,
                60f
        );

        assertEquals(1280, target.width);
        assertEquals(720, target.height);
        assertEquals(30f, target.frameRate, 0.01f);
    }

    private static VideoResourceGovernor.Snapshot snapshot(
            long availableMemory,
            long totalMemory,
            boolean lowMemory,
            int processors,
            double cpuLoad,
            int thermalStatus,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            long sourceBitrate,
            int sourceBitDepth,
            int displayWidth,
            int displayHeight,
            float displayRefreshRate,
            int reportedPerformance
    ) {
        return new VideoResourceGovernor.Snapshot(
                availableMemory,
                totalMemory,
                256L * 1024L * 1024L,
                lowMemory,
                processors,
                cpuLoad,
                thermalStatus,
                sourceWidth,
                sourceHeight,
                sourceFrameRate,
                sourceBitrate,
                sourceBitDepth,
                displayWidth,
                displayHeight,
                displayRefreshRate,
                reportedPerformance,
                false
        );
    }
}
