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

    @Nullable
    private CineFfmpegVideoDecoder decoder;

    public CineFfmpegVideoRenderer(
            long allowedJoiningTimeMs,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            int maxDroppedFramesToNotify
    ) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
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
        if (!CineFfmpegLibrary.supportsMimeType(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(
                C.FORMAT_HANDLED,
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
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
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
            currentDecoder.renderToSurface(outputBuffer, surface);
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
}
