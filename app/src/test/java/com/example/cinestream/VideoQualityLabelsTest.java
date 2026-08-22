package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoQualityLabelsTest {

    @Test
    public void labels8kInLandscapeAndPortrait() {
        assertEquals("8K", VideoQualityLabels.forDimensions(7680, 4320));
        assertEquals("8K", VideoQualityLabels.forDimensions(4320, 7680));
    }

    @Test
    public void preservesExistingResolutionLabels() {
        assertEquals("4K", VideoQualityLabels.forDimensions(3840, 2160));
        assertEquals("1080p", VideoQualityLabels.forDimensions(1920, 1080));
        assertEquals("720p", VideoQualityLabels.forDimensions(1280, 720));
    }
}
