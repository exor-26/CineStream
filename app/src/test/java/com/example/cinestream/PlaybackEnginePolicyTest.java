package com.example.cinestream;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackEnginePolicyTest {

    @Test
    public void decoderFailuresAreRecoverableCandidates() {
        assertTrue(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED));
        assertTrue(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FAILED));
        assertTrue(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES));
        assertTrue(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED));
    }

    @Test
    public void ioAndSourceFailuresAreNotDecoderFallbackCandidates() {
        assertFalse(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND));
        assertFalse(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED));
        assertFalse(PlaybackEnginePolicy.isDecoderFailureCode(
                PlaybackException.ERROR_CODE_PERMISSION_DENIED));
    }

    @Test
    public void bundledSoftwareVideoSupportIsNotOverclaimed() {
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/av01"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/x-vnd.on2.vp9"));
        assertFalse(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/avc"));
        assertFalse(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/hevc"));
        assertFalse(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/mp4v-es"));
    }

    @Test
    public void audioAndVideoSoftwarePreferencesComposeIndependently() {
        PlaybackEnginePolicy.DecoderMode video =
                PlaybackEnginePolicy.DecoderMode.HARDWARE_FIRST.withSoftwareVideo();
        assertEquals(PlaybackEnginePolicy.DecoderMode.SOFTWARE_VIDEO_FIRST, video);

        PlaybackEnginePolicy.DecoderMode both = video.withSoftwareAudio();
        assertEquals(PlaybackEnginePolicy.DecoderMode.SOFTWARE_AUDIO_VIDEO_FIRST, both);
        assertEquals(both, both.withSoftwareAudio());
        assertEquals(both, both.withSoftwareVideo());
    }
}
