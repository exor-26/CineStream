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

import com.example.cinestream.ffmpeg.CineFfmpegLibrary;
import com.example.cinestream.ffmpeg.CineFfmpegVideoRenderer;

import java.util.ArrayList;

/**
 * Central policy for decoder ordering and recovery.
 *
 * Platform video decoders remain first for normal playback because they are normally hardware
 * accelerated and far more power-efficient than software video extensions. Audio also starts
 * platform-first. Decoder preferences are switched independently after a genuine renderer failure:
 * FFmpeg may be preferred for audio while dav1d/libvpx/CineFFmpeg may be preferred for video.
 */
public final class PlaybackEnginePolicy {

    public enum DecoderMode {
        HARDWARE_FIRST(false, false),
        SOFTWARE_AUDIO_FIRST(true, false),
        SOFTWARE_VIDEO_FIRST(false, true),
        SOFTWARE_AUDIO_VIDEO_FIRST(true, true);

        final boolean preferSoftwareAudio;
        final boolean preferSoftwareVideo;

        DecoderMode(boolean preferSoftwareAudio, boolean preferSoftwareVideo) {
            this.preferSoftwareAudio = preferSoftwareAudio;
            this.preferSoftwareVideo = preferSoftwareVideo;
        }

        DecoderMode withSoftwareAudio() {
            if (preferSoftwareAudio) {
                return this;
            }
            return preferSoftwareVideo ? SOFTWARE_AUDIO_VIDEO_FIRST : SOFTWARE_AUDIO_FIRST;
        }

        DecoderMode withSoftwareVideo() {
            if (preferSoftwareVideo) {
                return this;
            }
            return preferSoftwareAudio ? SOFTWARE_AUDIO_VIDEO_FIRST : SOFTWARE_VIDEO_FIRST;
        }

        DecoderMode withoutSoftwareVideo() {
            if (!preferSoftwareVideo) {
                return this;
            }
            return preferSoftwareAudio ? SOFTWARE_AUDIO_FIRST : HARDWARE_FIRST;
        }
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
        return currentMode != null
                && !currentMode.preferSoftwareAudio
                && isDecoderFailure(error)
                && isAudioRendererFailure(error);
    }

    /** Returns whether the failed video can be retried using a bundled software video renderer. */
    public static boolean shouldRetryWithSoftwareVideo(
            DecoderMode currentMode,
            PlaybackException error
    ) {
        if (currentMode == null || currentMode.preferSoftwareVideo
                || !isDecoderFailure(error) || !isVideoRendererFailure(error)) {
            return false;
        }
        Format format = ((ExoPlaybackException) error).rendererFormat;
        return format != null && hasBundledSoftwareVideoDecoder(format.sampleMimeType);
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

    static boolean hasBundledSoftwareVideoDecoder(String mime) {
        return "video/av01".equals(mime)
                || "video/x-vnd.on2.vp9".equals(mime)
                || CineFfmpegLibrary.isDeclaredVideoMimeType(mime);
    }

    static boolean isVideoDecoderFailure(PlaybackException error) {
        return isDecoderFailure(error) && isVideoRendererFailure(error);
    }

    static Format getFailedVideoFormat(PlaybackException error) {
        if (!isVideoRendererFailure(error)) {
            return null;
        }
        return ((ExoPlaybackException) error).rendererFormat;
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

    private static boolean isVideoRendererFailure(PlaybackException error) {
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
                && format.sampleMimeType.startsWith("video/");
    }

    /**
     * Media3 exposes one extension ordering flag for both audio and video. CineStream deliberately
     * controls the tracks independently. The CineFFmpeg video renderer is appended in normal mode
     * so MediaCodec remains first, and inserted first only during a software-video recovery.
     */
    private static final class CineStreamRenderersFactory extends DefaultRenderersFactory {
        private static final int SOFTWARE_VIDEO_DROPPED_FRAMES_NOTIFY_THRESHOLD = 50;

        private final DecoderMode decoderMode;

        CineStreamRenderersFactory(Context context, DecoderMode decoderMode) {
            super(context);
            this.decoderMode = decoderMode != null ? decoderMode : DecoderMode.HARDWARE_FIRST;
            setExtensionRendererMode(
                    this.decoderMode.preferSoftwareAudio
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
                    decoderMode.preferSoftwareVideo
                            ? EXTENSION_RENDERER_MODE_PREFER
                            : EXTENSION_RENDERER_MODE_ON,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
            );

            CineFfmpegVideoRenderer ffmpegRenderer = new CineFfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    SOFTWARE_VIDEO_DROPPED_FRAMES_NOTIFY_THRESHOLD
            );
            if (decoderMode.preferSoftwareVideo) {
                out.add(0, ffmpegRenderer);
            } else {
                out.add(ffmpegRenderer);
            }
        }
    }
}
