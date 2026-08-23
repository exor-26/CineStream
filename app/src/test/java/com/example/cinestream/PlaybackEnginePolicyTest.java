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
    public void bundledSoftwareVideoSupportMatchesNativeFallbackSet() {
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/av01"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/x-vnd.on2.vp9"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/x-vnd.on2.vp8"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/avc"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/hevc"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/mp4v-es"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/mpeg2"));
        assertTrue(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/wvc1"));
        assertFalse(PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder("video/x-unknown"));
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

        assertEquals(
                PlaybackEnginePolicy.DecoderMode.SOFTWARE_AUDIO_FIRST,
                both.withoutSoftwareVideo()
        );
        assertEquals(
                PlaybackEnginePolicy.DecoderMode.HARDWARE_FIRST,
                video.withoutSoftwareVideo()
        );
    }

    @Test
    public void actualHardwareFailureOverridesOptimisticCapabilityTable() {
        assertTrue(PlaybackEnginePolicy.shouldAllowCompatibilityRecovery(
                true,
                DeviceVideoCapabilities.Support.SUPPORTED,
                true
        ));
        assertFalse(PlaybackEnginePolicy.shouldAllowCompatibilityRecovery(
                false,
                DeviceVideoCapabilities.Support.SUPPORTED,
                true
        ));
        assertFalse(PlaybackEnginePolicy.shouldAllowCompatibilityRecovery(
                true,
                DeviceVideoCapabilities.Support.SUPPORTED,
                false
        ));
    }

    @Test
    public void wrappedGovernorHandoffIsRecognized() {
        Throwable wrapped = new IllegalStateException(
                "renderer wrapper",
                new RuntimeException(
                        "nested",
                        new VideoResourceGovernor.HandoffException("too slow")
                )
        );
        assertTrue(PlaybackEnginePolicy.isGovernorHandoff(wrapped));
        assertFalse(PlaybackEnginePolicy.isGovernorHandoff(
                new IllegalStateException("ordinary failure")
        ));
    }
}
