package com.example.cinestream;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

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
}
