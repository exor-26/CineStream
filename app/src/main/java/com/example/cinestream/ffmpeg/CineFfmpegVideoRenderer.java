package com.example.cinestream.ffmpeg;

import android.os.Handler;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/**
 * Media3 video renderer backed by CineStream's minimal FFmpeg build.
 *
 * <p>Media3 still owns extraction, timestamps, seeking, frame dropping and A/V synchronization.
 * FFmpeg is used only to decode video formats that the platform path cannot reliably handle.
 */
@UnstableApi
public final class CineFfmpegVideoRenderer extends DecoderVideoRenderer {
    private static final String TAG = "CineFfmpegVideoRenderer";
    private static final int NUM_INPUT_BUFFERS = 3;
    private static final int NUM_OUTPUT_BUFFERS = 3;
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 2 * 1024 * 1024;

    private final boolean explicitSoftwareRecovery;
    private final int displayWidth;
    private final int displayHeight;

    @Nullable
    private CineFfmpegVideoDecoder decoder;

    public CineFfmpegVideoRenderer(
            long allowedJoiningTimeMs,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            int maxDroppedFramesToNotify,
            boolean explicitSoftwareRecovery,
            int displayWidth,
            int displayHeight
    ) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
        this.explicitSoftwareRecovery = explicitSoftwareRecovery;
        this.displayWidth = Math.max(1, displayWidth);
        this.displayHeight = Math.max(1, displayHeight);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    @RendererCapabilities.Capabilities
    public int supportsFormat(Format format) {
        String mimeType = format.sampleMimeType;
        if (mimeType == null || !MimeTypes.isVideo(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        // Do not load the native FFmpeg library while Media3 is merely probing renderers. The
        // library is part of this APK and is verified when createDecoder() is actually selected.
        if (!CineFfmpegLibrary.isDeclaredVideoMimeType(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(
                supportLevelForMode(explicitSoftwareRecovery),
                ADAPTIVE_NOT_SUPPORTED,
                TUNNELING_NOT_SUPPORTED
        );
    }

    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
    createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
            throws CineFfmpegDecoderException {
        int initialInputBufferSize = format.maxInputSize != Format.NO_VALUE
                ? format.maxInputSize
                : DEFAULT_INPUT_BUFFER_SIZE;
        int threads = chooseThreadCount(
                format.width,
                format.height,
                Runtime.getRuntime().availableProcessors()
        );
        CineFfmpegVideoDecoder newDecoder = new CineFfmpegVideoDecoder(
                NUM_INPUT_BUFFERS,
                NUM_OUTPUT_BUFFERS,
                initialInputBufferSize,
                threads,
                format
        );
        decoder = newDecoder;
        return newDecoder;
    }

    @Override
    protected void renderOutputBufferToSurface(
            VideoDecoderOutputBuffer outputBuffer,
            Surface surface
    ) throws CineFfmpegDecoderException {
        CineFfmpegVideoDecoder currentDecoder = decoder;
        if (currentDecoder == null) {
            outputBuffer.release();
            throw new CineFfmpegDecoderException("FFmpeg renderer has no decoder instance.");
        }
        try {
            int[] outputSize = fitWithinDisplay(
                    outputBuffer.width,
                    outputBuffer.height,
                    displayWidth,
                    displayHeight
            );
            currentDecoder.renderToSurface(
                    outputBuffer,
                    surface,
                    outputBuffer.timeUs,
                    outputSize[0],
                    outputSize[1]
            );
        } finally {
            outputBuffer.release();
        }
    }

    @Override
    protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
        CineFfmpegVideoDecoder currentDecoder = decoder;
        if (currentDecoder != null) {
            currentDecoder.setOutputMode(outputMode);
        }
    }

    static int supportLevelForMode(boolean explicitSoftwareRecovery) {
        return explicitSoftwareRecovery ? C.FORMAT_HANDLED : C.FORMAT_EXCEEDS_CAPABILITIES;
    }

    static int chooseThreadCount(int width, int height, int availableProcessors) {
        int processorLimit = Math.max(1, availableProcessors);
        int dimensionLimit;
        if (width <= 0 || height <= 0) {
            dimensionLimit = 2;
        } else {
            long pixels = (long) width * (long) height;
            if (pixels > 4096L * 2160L) {
                dimensionLimit = 2;
            } else if (pixels > 1920L * 1080L) {
                dimensionLimit = 4;
            } else {
                dimensionLimit = 8;
            }
        }
        return Math.max(1, Math.min(processorLimit, dimensionLimit));
    }

    static int[] fitWithinDisplay(
            int sourceWidth,
            int sourceHeight,
            int displayWidth,
            int displayHeight
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return new int[]{Math.max(1, sourceWidth), Math.max(1, sourceHeight)};
        }
        double scale = Math.min(
                1d,
                Math.min((double) displayWidth / sourceWidth, (double) displayHeight / sourceHeight)
        );
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new int[]{width, height};
    }
}
