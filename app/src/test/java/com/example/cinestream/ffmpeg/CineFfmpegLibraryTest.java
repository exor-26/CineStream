package com.example.cinestream.ffmpeg;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class CineFfmpegLibraryTest {

    @Test
    public void transformerLimitsEightKFrameThreadMemory() {
        assertEquals(
                2,
                CineFfmpegTransformerDecoderFactory.chooseThreadCount(7680, 4320, 8)
        );
        assertEquals(
                4,
                CineFfmpegTransformerDecoderFactory.chooseThreadCount(3840, 2160, 8)
        );
        assertEquals(
                8,
                CineFfmpegTransformerDecoderFactory.chooseThreadCount(1920, 1080, 8)
        );
        assertEquals(
                true,
                CineFfmpegTransformerDecoderFactory
                        .shouldDeferSoftwareDecodeToLowerFrameRate(60f, 60f)
        );
        assertEquals(
                true,
                CineFfmpegTransformerDecoderFactory
                        .shouldDiscardNonReferenceFrames(60f, 30f)
        );
    }

    @Test
    public void playbackMappingIncludesRealtimeFallbackCodecs() {
        assertEquals("h264", CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_H264));
        assertEquals("hevc", CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_H265));
        assertEquals("vp8", CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_VP8));
        assertNull(CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_DOLBY_VISION));
        assertNull(CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_AV1));
        assertNull(CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_VP9));
    }

    @Test
    public void transformerMappingAddsHeavyOfflineFallbackCodecs() {
        assertEquals("av1",
                CineFfmpegLibrary.codecNameForTransformerMimeType(MimeTypes.VIDEO_AV1));
        assertEquals("vp9",
                CineFfmpegLibrary.codecNameForTransformerMimeType(MimeTypes.VIDEO_VP9));
        assertEquals("hevc",
                CineFfmpegLibrary.codecNameForTransformerMimeType(
                        MimeTypes.VIDEO_DOLBY_VISION));
        assertEquals("h264",
                CineFfmpegLibrary.codecNameForTransformerMimeType(MimeTypes.VIDEO_H264));
        assertNull(CineFfmpegLibrary.codecNameForTransformerMimeType("video/x-unknown"));
    }

    @Test
    public void transformerUsesHevcHardwareQueryForDolbyVisionBaseLayer() {
        androidx.media3.common.Format dolbyVision = new androidx.media3.common.Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.05.12")
                .setWidth(7680)
                .setHeight(4320)
                .build();

        androidx.media3.common.Format platformFormat =
                CineFfmpegTransformerDecoderFactory.platformBaseLayerFormat(dolbyVision);

        assertEquals(MimeTypes.VIDEO_H265, platformFormat.sampleMimeType);
        assertNull(platformFormat.codecs);
        assertEquals(7680, platformFormat.width);
        assertEquals(4320, platformFormat.height);

        androidx.media3.common.Format h264 = new androidx.media3.common.Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .build();
        assertSame(h264,
                CineFfmpegTransformerDecoderFactory.platformBaseLayerFormat(h264));
    }
}
