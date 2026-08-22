package com.example.cinestream.ffmpeg;

import android.media.MediaCodec;
import android.media.metrics.LogSessionId;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;

import java.nio.ByteBuffer;

/**
 * Adds CineStream's FFmpeg video decoder as a last-resort Media3 Transformer input decoder.
 * Platform MediaCodec decoding remains first and continues to handle all normal export paths.
 */
@UnstableApi
public final class CineFfmpegTransformerDecoderFactory implements Codec.DecoderFactory {
    private static final String TAG = "CineFfmpegTransformer";

    private final Codec.DecoderFactory platformFactory;
    private final boolean forceFfmpegVideo;
    private final int outputWidth;
    private final int outputHeight;
    private final float outputFrameRate;

    public CineFfmpegTransformerDecoderFactory(Codec.DecoderFactory platformFactory) {
        this(
                platformFactory,
                false,
                Format.NO_VALUE,
                Format.NO_VALUE,
                Format.NO_VALUE
        );
    }

    public CineFfmpegTransformerDecoderFactory(
            Codec.DecoderFactory platformFactory,
            boolean forceFfmpegVideo
    ) {
        this(
                platformFactory,
                forceFfmpegVideo,
                Format.NO_VALUE,
                Format.NO_VALUE,
                Format.NO_VALUE
        );
    }

    public CineFfmpegTransformerDecoderFactory(
            Codec.DecoderFactory platformFactory,
            boolean forceFfmpegVideo,
            int outputWidth,
            int outputHeight,
            float outputFrameRate
    ) {
        this.platformFactory = platformFactory;
        this.forceFfmpegVideo = forceFfmpegVideo;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.outputFrameRate = outputFrameRate;
    }

    @Override
    public Codec createForAudioDecoding(
            Format format,
            @Nullable LogSessionId logSessionId
    ) throws ExportException {
        return platformFactory.createForAudioDecoding(format, logSessionId);
    }

    @Override
    public Codec createForVideoDecoding(
            Format format,
            Surface outputSurface,
            boolean requestSdrToneMapping,
            @Nullable LogSessionId logSessionId
    ) throws ExportException {
        if (forceFfmpegVideo
                && CineFfmpegLibrary.supportsTransformerMimeType(format.sampleMimeType)) {
            try {
                return new CineFfmpegTransformerCodec(
                        format,
                        outputSurface,
                        outputWidth,
                        outputHeight,
                        outputFrameRate
                );
            } catch (CineFfmpegDecoderException ffmpegError) {
                throw codecError(format, ffmpegError, ExportException.ERROR_CODE_DECODER_INIT_FAILED);
            }
        }
        try {
            Format platformFormat = platformBaseLayerFormat(format);
            if (platformFormat != format) {
                Log.i(TAG, "Trying HEVC hardware decode for Dolby Vision compatibility export");
            }
            return platformFactory.createForVideoDecoding(
                    platformFormat,
                    outputSurface,
                    requestSdrToneMapping,
                    logSessionId
            );
        } catch (ExportException platformError) {
            if (!CineFfmpegLibrary.supportsTransformerMimeType(format.sampleMimeType)) {
                throw platformError;
            }
            if (shouldDeferSoftwareDecodeToLowerFrameRate(
                    format.frameRate,
                    outputFrameRate
            )) {
                Log.i(TAG, "Deferring software video decode to the 30 fps compatibility tier");
                throw platformError;
            }
            try {
                return new CineFfmpegTransformerCodec(
                        format,
                        outputSurface,
                        outputWidth,
                        outputHeight,
                        outputFrameRate
                );
            } catch (CineFfmpegDecoderException ffmpegError) {
                platformError.addSuppressed(ffmpegError);
                throw platformError;
            }
        }
    }

    /**
     * Transformer output is an SDR H.264 compatibility file, so a non-Dolby HEVC decoder may be
     * used for the base layer before falling back to bundled FFmpeg. The original Format and CSD
     * remain intact for FFmpeg and extraction; only the MediaCodec query MIME/profile are relaxed.
     */
    static Format platformBaseLayerFormat(Format format) {
        if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
            return format;
        }
        return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(null)
                .build();
    }

    private static ExportException codecError(Format format, Throwable cause, int errorCode) {
        ExportException.CodecInfo codecInfo = new ExportException.CodecInfo(
                String.valueOf(format),
                true,
                true,
                "CineFFmpegVideoDecoder/Transformer"
        );
        return ExportException.createForCodec(cause, errorCode, codecInfo);
    }

    private static final class CineFfmpegTransformerCodec implements Codec {
        private static final int NUM_INPUT_BUFFERS = 2;
        private static final int NUM_OUTPUT_BUFFERS = 2;
        private static final int DEFAULT_INPUT_BUFFER_SIZE = 2 * 1024 * 1024;

        private final Format format;
        private final Format outputFormat;
        private final Surface outputSurface;
        private final int outputWidth;
        private final int outputHeight;
        private final CineFfmpegVideoDecoder decoder;
        private final MediaCodec.BufferInfo outputBufferInfo = new MediaCodec.BufferInfo();

        @Nullable private DecoderInputBuffer dequeuedInputBuffer;
        @Nullable private VideoDecoderOutputBuffer dequeuedOutputBuffer;
        private boolean inputEnded;
        private boolean outputEnded;
        private boolean released;

        CineFfmpegTransformerCodec(
                Format format,
                Surface outputSurface,
                int requestedOutputWidth,
                int requestedOutputHeight,
                float requestedOutputFrameRate
        )
                throws CineFfmpegDecoderException {
            this.format = format;
            this.outputSurface = outputSurface;
            outputWidth = requestedOutputWidth > 0 ? requestedOutputWidth : format.width;
            outputHeight = requestedOutputHeight > 0 ? requestedOutputHeight : format.height;
            outputFormat = format.buildUpon()
                    .setWidth(outputWidth)
                    .setHeight(outputHeight)
                    .build();
            int inputBufferSize = format.maxInputSize != Format.NO_VALUE
                    ? format.maxInputSize
                    : DEFAULT_INPUT_BUFFER_SIZE;
            int threads = chooseThreadCount(
                    format.width,
                    format.height,
                    Runtime.getRuntime().availableProcessors()
            );
            decoder = new CineFfmpegVideoDecoder(
                    NUM_INPUT_BUFFERS,
                    NUM_OUTPUT_BUFFERS,
                    inputBufferSize,
                    threads,
                    format,
                    true,
                    shouldDiscardNonReferenceFrames(
                            format.frameRate,
                            requestedOutputFrameRate
                    )
            );
            decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        }

        @Override
        public Format getConfigurationFormat() {
            return format;
        }

        @Override
        public String getName() {
            return decoder.getName() + "/Transformer";
        }

        @Override
        public Surface getInputSurface() {
            throw new IllegalStateException("A video decoder does not have an input Surface.");
        }

        @Override
        public int getMaxPendingFrameCount() {
            return NUM_OUTPUT_BUFFERS;
        }

        @Override
        public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer)
                throws ExportException {
            ensureNotReleased();
            if (inputEnded) {
                return false;
            }
            if (dequeuedInputBuffer == null) {
                try {
                    dequeuedInputBuffer = decoder.dequeueInputBuffer();
                } catch (CineFfmpegDecoderException error) {
                    throw codecError(error, ExportException.ERROR_CODE_DECODING_FAILED);
                }
                if (dequeuedInputBuffer == null) {
                    return false;
                }
            }
            inputBuffer.clear();
            inputBuffer.data = dequeuedInputBuffer.data;
            return true;
        }

        @Override
        public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws ExportException {
            ensureNotReleased();
            DecoderInputBuffer nativeInput = dequeuedInputBuffer;
            if (nativeInput == null || nativeInput.data != inputBuffer.data) {
                throw codecError(
                        new IllegalStateException("FFmpeg input was not dequeued."),
                        ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK
                );
            }
            nativeInput.timeUs = inputBuffer.timeUs;
            if (inputBuffer.isEndOfStream()) {
                nativeInput.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
            }
            try {
                decoder.queueInputBuffer(nativeInput);
            } catch (CineFfmpegDecoderException error) {
                throw codecError(error, ExportException.ERROR_CODE_DECODING_FAILED);
            } finally {
                dequeuedInputBuffer = null;
                inputBuffer.data = null;
            }
        }

        @Override
        public void signalEndOfInputStream() throws ExportException {
            throw codecError(
                    new IllegalStateException("Decoder EOS must be queued as an input buffer."),
                    ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK
            );
        }

        @Override
        public Format getInputFormat() {
            return format;
        }

        @Override
        public Format getOutputFormat() {
            return outputFormat;
        }

        @Nullable
        @Override
        public ByteBuffer getOutputBuffer() {
            return null;
        }

        @Nullable
        @Override
        public MediaCodec.BufferInfo getOutputBufferInfo() throws ExportException {
            ensureNotReleased();
            if (dequeuedOutputBuffer == null && !outputEnded) {
                try {
                    dequeuedOutputBuffer = decoder.dequeueOutputBuffer();
                } catch (CineFfmpegDecoderException error) {
                    throw codecError(error, ExportException.ERROR_CODE_DECODING_FAILED);
                }
            }
            VideoDecoderOutputBuffer output = dequeuedOutputBuffer;
            if (output == null) {
                return null;
            }
            if (output.isEndOfStream()) {
                output.release();
                dequeuedOutputBuffer = null;
                outputEnded = true;
                return null;
            }
            outputBufferInfo.set(0, 0, output.timeUs, 0);
            return outputBufferInfo;
        }

        @Override
        public void releaseOutputBuffer(boolean render) throws ExportException {
            VideoDecoderOutputBuffer output = dequeuedOutputBuffer;
            releaseCurrentOutput(render, output != null ? output.timeUs : C.TIME_UNSET);
        }

        @Override
        public void releaseOutputBuffer(long renderPresentationTimeUs) throws ExportException {
            releaseCurrentOutput(true, renderPresentationTimeUs);
        }

        @Override
        public boolean isEnded() {
            return outputEnded && dequeuedOutputBuffer == null;
        }

        @Override
        public void release() {
            if (released) {
                return;
            }
            released = true;
            if (dequeuedOutputBuffer != null) {
                dequeuedOutputBuffer.release();
                dequeuedOutputBuffer = null;
            }
            decoder.release();
        }

        private void releaseCurrentOutput(boolean render, long presentationTimeUs)
                throws ExportException {
            ensureNotReleased();
            VideoDecoderOutputBuffer output = dequeuedOutputBuffer;
            if (output == null) {
                throw codecError(
                        new IllegalStateException("No FFmpeg output buffer is dequeued."),
                        ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK
                );
            }
            try {
                if (render) {
                    decoder.renderToSurface(
                            output,
                            outputSurface,
                            presentationTimeUs,
                            outputWidth,
                            outputHeight
                    );
                }
            } catch (CineFfmpegDecoderException error) {
                throw codecError(error, ExportException.ERROR_CODE_DECODING_FAILED);
            } finally {
                output.release();
                dequeuedOutputBuffer = null;
            }
        }

        private void ensureNotReleased() throws ExportException {
            if (released) {
                throw codecError(
                        new IllegalStateException("FFmpeg Transformer decoder is released."),
                        ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK
                );
            }
        }

        private ExportException codecError(Throwable cause, int errorCode) {
            ExportException.CodecInfo codecInfo = new ExportException.CodecInfo(
                    String.valueOf(format),
                    true,
                    true,
                    getName()
            );
            return ExportException.createForCodec(cause, errorCode, codecInfo);
        }
    }

    static int chooseThreadCount(int width, int height, int availableProcessors) {
        int processors = Math.max(1, availableProcessors);
        long pixels = Math.max(0, width) * (long) Math.max(0, height);
        if (pixels > 4096L * 2160L) {
            // A single 8K 10-bit 4:2:0 frame is about 100 MB. Frame-threaded HEVC decoding can
            // retain one or more full frames per thread, so a high thread count lets Android's
            // low-memory killer terminate the player on otherwise capable older devices.
            return Math.min(2, processors);
        }
        if (pixels > 1920L * 1080L) {
            return Math.min(4, processors);
        }
        return Math.min(8, processors);
    }

    static boolean shouldDeferSoftwareDecodeToLowerFrameRate(
            float sourceFrameRate,
            float outputFrameRate
    ) {
        return sourceFrameRate > 31f && outputFrameRate > 31f;
    }

    static boolean shouldDiscardNonReferenceFrames(
            float sourceFrameRate,
            float outputFrameRate
    ) {
        return sourceFrameRate > 31f
                && outputFrameRate > 0f
                && outputFrameRate <= 31f;
    }
}
