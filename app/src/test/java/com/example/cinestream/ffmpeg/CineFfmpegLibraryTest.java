package com.example.cinestream.ffmpeg;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
        assertEquals("av1", CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_AV1));
        assertEquals("vp9", CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_VP9));
        assertNull(CineFfmpegLibrary.codecNameForMimeType(MimeTypes.VIDEO_DOLBY_VISION));
    }

    @Test
    public void realtimeRendererIsSecondaryUntilExplicitRecovery() {
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES,
                CineFfmpegVideoRenderer.supportLevelForMode(false));
        assertEquals(C.FORMAT_HANDLED,
                CineFfmpegVideoRenderer.supportLevelForMode(true));
    }

    @Test
    public void realtimeDecoderThreadsAreBoundedBySourceComplexityAndCpu() {
        assertEquals(2, CineFfmpegVideoRenderer.chooseThreadCount(7680, 4320, 12));
        assertEquals(4, CineFfmpegVideoRenderer.chooseThreadCount(3840, 2160, 12));
        assertEquals(4, CineFfmpegVideoRenderer.chooseThreadCount(
                3840, 2160, 8, 1024L * 1024L * 1024L
        ));
        assertEquals(4, CineFfmpegVideoRenderer.chooseThreadCount(
                3840, 2160, 8, 512L * 1024L * 1024L
        ));
        assertEquals(2, CineFfmpegVideoRenderer.chooseThreadCount(
                3840, 2160, 8, 256L * 1024L * 1024L
        ));
        assertEquals(8, CineFfmpegVideoRenderer.chooseThreadCount(1920, 1080, 12));
        assertEquals(3, CineFfmpegVideoRenderer.chooseThreadCount(1920, 1080, 3));
        assertEquals(2, CineFfmpegVideoRenderer.chooseThreadCount(-1, -1, 12));
        assertEquals(1, CineFfmpegVideoRenderer.chooseThreadCount(7680, 4320, 0));
    }

    @Test
    public void realtimeSurfaceOutputIsDisplayBoundedWithoutUpscaling() {
        assertEquals(1920,
                CineFfmpegVideoRenderer.fitWithinDisplay(7680, 4320, 1920, 1080)[0]);
        assertEquals(1080,
                CineFfmpegVideoRenderer.fitWithinDisplay(7680, 4320, 1920, 1080)[1]);
        assertEquals(1280,
                CineFfmpegVideoRenderer.fitWithinDisplay(1280, 720, 1920, 1080)[0]);
        assertEquals(720,
                CineFfmpegVideoRenderer.fitWithinDisplay(1280, 720, 1920, 1080)[1]);
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
    public void dolbyVisionDetectionUsesStreamFormatNotDeviceIdentity() {
        assertTrue(CineFfmpegLibrary.isDolbyVisionFormat(
                MimeTypes.VIDEO_DOLBY_VISION,
                null
        ));
        assertTrue(CineFfmpegLibrary.isDolbyVisionFormat(
                MimeTypes.VIDEO_H265,
                "dvh1.05.06"
        ));
        assertTrue(CineFfmpegLibrary.isDolbyVisionFormat(
                MimeTypes.VIDEO_H265,
                "DVHE.08.07"
        ));
        assertFalse(CineFfmpegLibrary.isDolbyVisionFormat(
                MimeTypes.VIDEO_H265,
                "hvc1.2.4.L153.B0"
        ));
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
