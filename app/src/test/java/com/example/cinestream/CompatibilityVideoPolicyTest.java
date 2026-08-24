package com.example.cinestream;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompatibilityVideoPolicyTest {

    @Test
    public void final480FrameRateVariantIsDistinct() {
        CompatibilityVideoPolicy.Target p480_30 =
                new CompatibilityVideoPolicy.Target(854, 480, 30f);
        CompatibilityVideoPolicy.Target p480_24 =
                new CompatibilityVideoPolicy.Target(854, 480, 24f);

        assertTrue(CompatibilityVideoPolicy.isAnyLowerFrameRateVariant(p480_30, p480_24));
        assertFalse(CompatibilityVideoPolicy.isAnyLowerFrameRateVariant(p480_24, p480_30));
    }

    @Test
    public void compatibilityCacheRejectsPartialExports() {
        assertTrue(CompatibilityVideoTranscoder.isDurationComplete(30_000L, 29_000L));
        assertFalse(CompatibilityVideoTranscoder.isDurationComplete(30_000L, 2_000L));
        assertFalse(CompatibilityVideoTranscoder.isDurationComplete(30_000L, 0L));
        assertTrue(CompatibilityVideoTranscoder.isDurationComplete(0L, 2_000L));
    }

    @Test
    public void compatibilityCacheRejectsCompletelyBlackFrames() {
        assertFalse(CompatibilityVideoTranscoder.hasVisiblePixels(new int[100]));
        int[] visibleFrame = new int[100];
        visibleFrame[42] = 0xff102030;
        assertTrue(CompatibilityVideoTranscoder.hasVisiblePixels(visibleFrame));
    }

    @Test
    public void fourKLandscapeStartsAt1080pWithoutAspectDistortion() {
        List<CompatibilityVideoPolicy.Target> targets =
                CompatibilityVideoPolicy.buildCandidates(3840, 2160, 30f);

        assertFalse(targets.isEmpty());
        assertEquals(1920, targets.get(0).width);
        assertEquals(1080, targets.get(0).height);
        assertEquals(30f, targets.get(0).frameRate, 0.01f);
    }

    @Test
    public void highFrameRateAlsoOffersThirtyFpsAtEachQualityTier() {
        List<CompatibilityVideoPolicy.Target> targets =
                CompatibilityVideoPolicy.buildCandidates(3840, 2160, 60f);

        assertTrue(targets.size() >= 2);
        assertEquals(1920, targets.get(0).width);
        assertEquals(1080, targets.get(0).height);
        assertEquals(60f, targets.get(0).frameRate, 0.01f);
        assertEquals(1920, targets.get(1).width);
        assertEquals(1080, targets.get(1).height);
        assertEquals(30f, targets.get(1).frameRate, 0.01f);
        assertTrue(CompatibilityVideoPolicy.isLowerFrameRateVariant(
                targets.get(0),
                targets.get(1)
        ));
        assertTrue(targets.stream().anyMatch(target ->
                target.height == 480 && Math.abs(target.frameRate - 24f) < 0.5f));
    }

    @Test
    public void portraitTargetsStayPortraitAndEvenSized() {
        List<CompatibilityVideoPolicy.Target> targets =
                CompatibilityVideoPolicy.buildCandidates(2160, 3840, 30f);

        assertFalse(targets.isEmpty());
        CompatibilityVideoPolicy.Target first = targets.get(0);
        assertEquals(1080, first.width);
        assertEquals(1920, first.height);
        assertEquals(0, first.width % 2);
        assertEquals(0, first.height % 2);
    }

    @Test
    public void smallerVideoIsNeverUpscaled() {
        int[] result = CompatibilityVideoPolicy.fitWithin(1280, 720, 1920, 1080);
        assertEquals(1280, result[0]);
        assertEquals(720, result[1]);
    }

    @Test
    public void cacheEstimateScalesWithQualityAndDuration() {
        CompatibilityVideoPolicy.Target highQuality =
                new CompatibilityVideoPolicy.Target(1920, 1080, 60f);
        CompatibilityVideoPolicy.Target compatibility =
                new CompatibilityVideoPolicy.Target(1280, 720, 30f);

        long highQualityEstimate =
                CompatibilityVideoTranscoder.estimateRequiredBytes(highQuality, 60_000L);
        long compatibilityEstimate =
                CompatibilityVideoTranscoder.estimateRequiredBytes(compatibility, 60_000L);
        long longCompatibilityEstimate =
                CompatibilityVideoTranscoder.estimateRequiredBytes(compatibility, 120_000L);

        assertTrue(highQualityEstimate > compatibilityEstimate);
        assertTrue(longCompatibilityEstimate > compatibilityEstimate);
    }

    @Test
    public void unknownDurationUsesConservativeCacheEstimate() {
        CompatibilityVideoPolicy.Target target =
                new CompatibilityVideoPolicy.Target(1920, 1080, 30f);

        assertTrue(CompatibilityVideoTranscoder.estimateRequiredBytes(target, 0L)
                >= 128L * 1024L * 1024L);
    }

    @Test
    public void resourceCeilingRejectsHigherResolutionOrFrameRate() {
        CompatibilityVideoPolicy.Target ceiling =
                new CompatibilityVideoPolicy.Target(1280, 720, 30f);
        assertTrue(CompatibilityVideoPolicy.isWithinCeiling(
                new CompatibilityVideoPolicy.Target(1280, 720, 30f),
                ceiling
        ));
        assertFalse(CompatibilityVideoPolicy.isWithinCeiling(
                new CompatibilityVideoPolicy.Target(1920, 1080, 30f),
                ceiling
        ));
        assertFalse(CompatibilityVideoPolicy.isWithinCeiling(
                new CompatibilityVideoPolicy.Target(1280, 720, 60f),
                ceiling
        ));
    }
}
