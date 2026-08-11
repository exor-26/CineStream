package com.example.cinestream.ffmpeg;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * FFmpeg software video decoder that keeps decoded AVFrames in native memory until Media3 releases
 * the corresponding output buffer. No decoded pixel plane is copied through Java.
 */
@UnstableApi
final class CineFfmpegVideoDecoder extends
        SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, CineFfmpegDecoderException> {

    private static final int NATIVE_RESULT_FRAME = 0;
    private static final int NATIVE_RESULT_NO_FRAME = 1;
    private static final int NATIVE_RESULT_ERROR = -1;

    private final String codecName;
    private long nativeContext;

    @C.VideoOutputMode
    private volatile int outputMode = C.VIDEO_OUTPUT_MODE_NONE;

    CineFfmpegVideoDecoder(
            int numInputBuffers,
            int numOutputBuffers,
            int initialInputBufferSize,
            int threads,
            Format format
    ) throws CineFfmpegDecoderException {
        super(
                new DecoderInputBuffer[numInputBuffers],
                new VideoDecoderOutputBuffer[numOutputBuffers]
        );

        if (!CineFfmpegLibrary.isAvailable()) {
            throw new CineFfmpegDecoderException("CineStream FFmpeg native library is unavailable.");
        }

        codecName = CineFfmpegLibrary.codecNameForMimeType(format.sampleMimeType);
        if (codecName == null || !CineFfmpegLibrary.supportsMimeType(format.sampleMimeType)) {
            throw new CineFfmpegDecoderException(
                    "No bundled FFmpeg video decoder for " + format.sampleMimeType
            );
        }

        nativeContext = nativeInitialize(
                codecName,
                concatenateInitializationData(format.initializationData),
                threads,
                format.rotationDegrees,
                format.width,
                format.height
        );
        if (nativeContext == 0L) {
            throw new CineFfmpegDecoderException("Unable to initialize FFmpeg decoder " + codecName);
        }

        setInitialInputBufferSize(Math.max(64 * 1024, initialInputBufferSize));
    }

    @Nullable
    private static byte[] concatenateInitializationData(List<byte[]> initializationData) {
        if (initializationData == null || initializationData.isEmpty()) {
            return null;
        }
        int total = 0;
        for (byte[] item : initializationData) {
            if (item != null) {
                total += item.length;
            }
        }
        if (total == 0) {
            return null;
        }
        byte[] data = new byte[total];
        int offset = 0;
        for (byte[] item : initializationData) {
            if (item == null || item.length == 0) {
                continue;
            }
            System.arraycopy(item, 0, data, offset, item.length);
            offset += item.length;
        }
        return data;
    }

    @Override
    public String getName() {
        String version = CineFfmpegLibrary.getVersion();
        return "CineFFmpegVideoDecoder(" + codecName + ", "
                + (version != null ? version : "unknown") + ")";
    }

    void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws CineFfmpegDecoderException {
        long frame = outputBuffer.decoderPrivate;
        if (frame == 0L) {
            throw new CineFfmpegDecoderException("FFmpeg output buffer has no native frame.");
        }
        if (nativeRenderFrame(nativeContext, frame, surface) != NATIVE_RESULT_FRAME) {
            throw new CineFfmpegDecoderException("Unable to render FFmpeg frame to Surface.");
        }
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
        return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected CineFfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new CineFfmpegDecoderException("Unexpected FFmpeg video decoder error.", error);
    }

    @Nullable
    @Override
    protected CineFfmpegDecoderException decode(
            DecoderInputBuffer inputBuffer,
            VideoDecoderOutputBuffer outputBuffer,
            boolean reset
    ) {
        if (reset && nativeFlush(nativeContext) != NATIVE_RESULT_FRAME) {
            return new CineFfmpegDecoderException("Unable to flush FFmpeg video decoder.");
        }

        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int size = inputData.remaining();
        boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
        int result = nativeDecodePacket(
                nativeContext,
                inputData,
                inputData.position(),
                size,
                inputBuffer.timeUs,
                outputMode,
                outputBuffer,
                decodeOnly
        );

        if (result == NATIVE_RESULT_ERROR) {
            return new CineFfmpegDecoderException("FFmpeg failed while decoding " + codecName + ".");
        }
        if (result == NATIVE_RESULT_NO_FRAME) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        }

        outputBuffer.format = inputBuffer.format;
        return null;
    }

    @Override
    protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        long frame = outputBuffer.decoderPrivate;
        if (frame != 0L) {
            if (nativeContext != 0L) {
                nativeReleaseFrame(nativeContext, frame);
            }
            outputBuffer.decoderPrivate = 0L;
        }
        super.releaseOutputBuffer(outputBuffer);
    }

    @Override
    public void release() {
        super.release();
        if (nativeContext != 0L) {
            nativeRelease(nativeContext);
            nativeContext = 0L;
        }
    }

    private static native long nativeInitialize(
            String codecName,
            @Nullable byte[] extraData,
            int threads,
            int rotationDegrees,
            int width,
            int height
    );

    private static native int nativeDecodePacket(
            long context,
            ByteBuffer encodedData,
            int offset,
            int length,
            long presentationTimeUs,
            int outputMode,
            VideoDecoderOutputBuffer outputBuffer,
            boolean decodeOnly
    );

    private static native int nativeFlush(long context);

    private static native int nativeRenderFrame(long context, long frame, Surface surface);

    private static native void nativeReleaseFrame(long context, long frame);

    private static native void nativeRelease(long context);
}
