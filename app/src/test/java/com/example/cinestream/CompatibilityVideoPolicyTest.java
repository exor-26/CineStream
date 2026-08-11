package com.example.cinestream;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompatibilityVideoPolicyTest {

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
}
