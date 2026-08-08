package com.example.cinestream;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

/**
 * Central policy for decoder ordering and recovery.
 *
 * Platform video decoders always remain first because they are normally hardware accelerated and
 * far more power-efficient than software video extensions. Audio starts platform-first too. If an
 * actual audio decoder failure occurs, CineStream can rebuild once with extension audio renderers
 * preferred so the bundled FFmpeg audio decoder gets first chance, while video still stays
 * MediaCodec-first.
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
        return new CineStreamRenderersFactory(context, decoderMode);
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

    /**
     * Media3 exposes one extension ordering flag for both audio and video. During an audio recovery
     * we need FFmpeg audio before MediaCodec audio, but we must not also move dav1d/libvpx ahead of
     * hardware video decoders. Force the video side to EXTENSION_RENDERER_MODE_ON in every mode.
     */
    private static final class CineStreamRenderersFactory extends DefaultRenderersFactory {

        CineStreamRenderersFactory(Context context, DecoderMode decoderMode) {
            super(context);
            setExtensionRendererMode(
                    decoderMode == DecoderMode.SOFTWARE_AUDIO_FIRST
                            ? EXTENSION_RENDERER_MODE_PREFER
                            : EXTENSION_RENDERER_MODE_ON
            );
            setEnableDecoderFallback(true);
        }

        @Override
        protected void buildVideoRenderers(
                Context context,
                int extensionRendererMode,
                MediaCodecSelector mediaCodecSelector,
                boolean enableDecoderFallback,
                Handler eventHandler,
                VideoRendererEventListener eventListener,
                long allowedVideoJoiningTimeMs,
                ArrayList<Renderer> out
        ) {
            super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_ON,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
            );
        }
    }
}
