package com.example.cinestream;

import android.content.Context;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;

/**
 * Central policy for decoder ordering and recovery.
 *
 * The normal path prefers Android platform decoders because they can use device hardware and are
 * generally the most power-efficient option. If an audio decoder actually fails, the player can
 * rebuild once with extension renderers preferred so the bundled FFmpeg audio decoder gets first
 * chance at formats such as E-AC-3, DTS/DTS-HD and TrueHD.
 */
public final class PlaybackEnginePolicy {

    public enum DecoderMode {
        HARDWARE_FIRST,
        SOFTWARE_AUDIO_FIRST
    }

    private PlaybackEnginePolicy() {
    }

    public static DefaultRenderersFactory createRenderersFactory(
            Context context,
            DecoderMode decoderMode
    ) {
        int extensionMode = decoderMode == DecoderMode.SOFTWARE_AUDIO_FIRST
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;

        return new DefaultRenderersFactory(context)
                .setExtensionRendererMode(extensionMode)
                .setEnableDecoderFallback(true);
    }

    public static boolean shouldRetryWithSoftwareAudio(
            DecoderMode currentMode,
            PlaybackException error
    ) {
        return currentMode == DecoderMode.HARDWARE_FIRST
                && isDecoderFailure(error)
                && isAudioRendererFailure(error);
    }

    static boolean isDecoderFailure(PlaybackException error) {
        if (error == null) {
            return false;
        }
        return isDecoderFailureCode(error.errorCode);
    }

    static boolean isDecoderFailureCode(int errorCode) {
        return errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED;
    }

    private static boolean isAudioRendererFailure(PlaybackException error) {
        if (!(error instanceof ExoPlaybackException)) {
            return false;
        }
        ExoPlaybackException exoError = (ExoPlaybackException) error;
        if (exoError.type != ExoPlaybackException.TYPE_RENDERER) {
            return false;
        }
        Format format = exoError.rendererFormat;
        return format != null
                && format.sampleMimeType != null
                && format.sampleMimeType.startsWith("audio/");
    }
}
