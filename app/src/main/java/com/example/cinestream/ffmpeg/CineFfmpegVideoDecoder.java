package com.example.cinestream.ffmpeg;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/**
 * FFmpeg software video decoder that keeps decoded AVFrames in native memory until Media3 releases
 * the corresponding output buffer. No decoded pixel plane is copied through Java.
 *
 * <p>This decoder intentionally implements Media3's {@link Decoder} contract directly rather than
 * extending SimpleDecoder. FFmpeg video decoding is not strictly one-input/one-output: reordered
 * frames may be delayed and must be drained before EOS. The dedicated decode thread therefore sends
 * each compressed packet once, drains every currently available frame, sends a null packet at EOS,
 * drains all delayed frames, and publishes Media3 EOS only after FFmpeg reports AVERROR_EOF.
 */
@UnstableApi
final class CineFfmpegVideoDecoder implements
        Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, CineFfmpegDecoderException> {

    private static final int NATIVE_RESULT_FRAME = 0;
    private static final int NATIVE_RESULT_NO_FRAME = 1;
    private static final int NATIVE_RESULT_END_OF_STREAM = 2;
    private static final int NATIVE_RESULT_ERROR = -1;

    private final Object lock = new Object();
    private final ArrayDeque<DecoderInputBuffer> availableInputBuffers = new ArrayDeque<>();
    private final ArrayDeque<DecoderInputBuffer> queuedInputBuffers = new ArrayDeque<>();
    private final ArrayDeque<VideoDecoderOutputBuffer> availableOutputBuffers = new ArrayDeque<>();
    private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputBuffers = new ArrayDeque<>();
    private final String codecName;
    private final Format format;
    private final Thread decodeThread;

    @Nullable
    private DecoderInputBuffer dequeuedInputBuffer;
    @Nullable
    private CineFfmpegDecoderException exception;

    private volatile long nativeContext;
    @C.VideoOutputMode
    private volatile int outputMode = C.VIDEO_OUTPUT_MODE_NONE;

    private boolean released;
    private boolean nativeFlushPending;
    private int flushGeneration;
    private int skippedOutputBufferCount;
    private long outputStartTimeUs = C.TIME_UNSET;

    CineFfmpegVideoDecoder(
            int numInputBuffers,
            int numOutputBuffers,
            int initialInputBufferSize,
            int threads,
            Format format
    ) throws CineFfmpegDecoderException {
        this(
                numInputBuffers,
                numOutputBuffers,
                initialInputBufferSize,
                threads,
                format,
                false,
                false
        );
    }

    CineFfmpegVideoDecoder(
            int numInputBuffers,
            int numOutputBuffers,
            int initialInputBufferSize,
            int threads,
            Format format,
            boolean transformerInput
    ) throws CineFfmpegDecoderException {
        this(
                numInputBuffers,
                numOutputBuffers,
                initialInputBufferSize,
                threads,
                format,
                transformerInput,
                false
        );
    }

    CineFfmpegVideoDecoder(
            int numInputBuffers,
            int numOutputBuffers,
            int initialInputBufferSize,
            int threads,
            Format format,
            boolean transformerInput,
            boolean discardNonReferenceFrames
    ) throws CineFfmpegDecoderException {
        if (!CineFfmpegLibrary.isAvailable()) {
            throw new CineFfmpegDecoderException("CineStream FFmpeg native library is unavailable.");
        }

        codecName = transformerInput
                ? CineFfmpegLibrary.codecNameForTransformerMimeType(format.sampleMimeType)
                : CineFfmpegLibrary.codecNameForMimeType(format.sampleMimeType);
        boolean supported = transformerInput
                ? CineFfmpegLibrary.supportsTransformerMimeType(format.sampleMimeType)
                : CineFfmpegLibrary.supportsMimeType(format.sampleMimeType);
        if (codecName == null || !supported) {
            throw new CineFfmpegDecoderException(
                    "No bundled FFmpeg video decoder for " + format.sampleMimeType
            );
        }
        this.format = format;

        nativeContext = nativeInitialize(
                codecName,
                concatenateInitializationData(format.initializationData),
                threads,
                format.rotationDegrees,
                format.width,
                format.height,
                discardNonReferenceFrames
        );
        if (nativeContext == 0L) {
            throw new CineFfmpegDecoderException("Unable to initialize FFmpeg decoder " + codecName);
        }

        int inputBufferSize = Math.max(64 * 1024, initialInputBufferSize);
        for (int i = 0; i < numInputBuffers; i++) {
            DecoderInputBuffer inputBuffer = new DecoderInputBuffer(
                    DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT
            );
            inputBuffer.ensureSpaceForWrite(inputBufferSize);
            availableInputBuffers.addLast(inputBuffer);
        }
        for (int i = 0; i < numOutputBuffers; i++) {
            availableOutputBuffers.addLast(new VideoDecoderOutputBuffer(this::releaseOutputBuffer));
        }

        decodeThread = new Thread(this::runDecodeLoop, "CineStream:FFmpegVideo");
        decodeThread.start();
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

    @Override
    public void setOutputStartTimeUs(long outputStartTimeUs) {
        synchronized (lock) {
            this.outputStartTimeUs = outputStartTimeUs;
        }
    }

    @Nullable
    @Override
    public DecoderInputBuffer dequeueInputBuffer() throws CineFfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            if (dequeuedInputBuffer != null) {
                throw new IllegalStateException("An FFmpeg input buffer is already dequeued.");
            }
            DecoderInputBuffer inputBuffer = availableInputBuffers.pollFirst();
            if (inputBuffer != null) {
                inputBuffer.clear();
                dequeuedInputBuffer = inputBuffer;
            }
            return inputBuffer;
        }
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer)
            throws CineFfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            if (inputBuffer != dequeuedInputBuffer) {
                throw new IllegalArgumentException("Queued FFmpeg input buffer was not dequeued.");
            }
            queuedInputBuffers.addLast(inputBuffer);
            dequeuedInputBuffer = null;
            lock.notifyAll();
        }
    }

    @Nullable
    @Override
    public VideoDecoderOutputBuffer dequeueOutputBuffer() throws CineFfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            return queuedOutputBuffers.pollFirst();
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            flushGeneration++;
            nativeFlushPending = true;
            skippedOutputBufferCount = 0;

            if (dequeuedInputBuffer != null) {
                recycleInputBufferLocked(dequeuedInputBuffer);
                dequeuedInputBuffer = null;
            }
            while (!queuedInputBuffers.isEmpty()) {
                recycleInputBufferLocked(queuedInputBuffers.removeFirst());
            }
            while (!queuedOutputBuffers.isEmpty()) {
                recycleOutputBufferLocked(queuedOutputBuffers.removeFirst());
            }
            lock.notifyAll();
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            lock.notifyAll();
        }

        try {
            decodeThread.join();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }

        long contextToRelease;
        synchronized (lock) {
            if (dequeuedInputBuffer != null) {
                dequeuedInputBuffer.clear();
                dequeuedInputBuffer = null;
            }
            queuedInputBuffers.clear();
            availableInputBuffers.clear();

            while (!queuedOutputBuffers.isEmpty()) {
                VideoDecoderOutputBuffer outputBuffer = queuedOutputBuffers.removeFirst();
                releaseNativeFrameLocked(outputBuffer);
                outputBuffer.clear();
            }
            while (!availableOutputBuffers.isEmpty()) {
                availableOutputBuffers.removeFirst().clear();
            }

            contextToRelease = nativeContext;
            nativeContext = 0L;
        }
        if (contextToRelease != 0L) {
            nativeRelease(contextToRelease);
        }
    }

    void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws CineFfmpegDecoderException {
        renderToSurface(outputBuffer, surface, outputBuffer.timeUs);
    }

    void renderToSurface(
            VideoDecoderOutputBuffer outputBuffer,
            Surface surface,
            long presentationTimeUs
    ) throws CineFfmpegDecoderException {
        renderToSurface(
                outputBuffer,
                surface,
                presentationTimeUs,
                outputBuffer.width,
                outputBuffer.height
        );
    }

    void renderToSurface(
            VideoDecoderOutputBuffer outputBuffer,
            Surface surface,
            long presentationTimeUs,
            int outputWidth,
            int outputHeight
    ) throws CineFfmpegDecoderException {
        long context = nativeContext;
        long frame = outputBuffer.decoderPrivate;
        if (context == 0L || frame == 0L) {
            throw new CineFfmpegDecoderException("FFmpeg output buffer has no native frame.");
        }
        if (nativeRenderFrame(
                context,
                frame,
                surface,
                presentationTimeUs,
                outputWidth,
                outputHeight
        )
                != NATIVE_RESULT_FRAME) {
            throw new CineFfmpegDecoderException("Unable to render FFmpeg frame to Surface.");
        }
    }

    private void runDecodeLoop() {
        try {
            while (true) {
                DecoderInputBuffer inputBuffer = null;
                int generation = 0;
                boolean flushNative = false;

                synchronized (lock) {
                    while (!released && !nativeFlushPending && queuedInputBuffers.isEmpty()) {
                        lock.wait();
                    }
                    if (released) {
                        return;
                    }
                    if (nativeFlushPending) {
                        nativeFlushPending = false;
                        flushNative = true;
                        generation = flushGeneration;
                    } else {
                        inputBuffer = queuedInputBuffers.removeFirst();
                        generation = flushGeneration;
                    }
                }

                if (flushNative) {
                    long context = nativeContext;
                    if (context == 0L || nativeFlush(context) != NATIVE_RESULT_FRAME) {
                        setException(new CineFfmpegDecoderException(
                                "Unable to flush FFmpeg video decoder."
                        ));
                        return;
                    }
                    continue;
                }

                if (inputBuffer == null) {
                    continue;
                }
                try {
                    if (inputBuffer.isEndOfStream()) {
                        processEndOfStream(inputBuffer, generation);
                    } else {
                        processCompressedInput(inputBuffer, generation);
                    }
                } finally {
                    synchronized (lock) {
                        if (!released) {
                            recycleInputBufferLocked(inputBuffer);
                            lock.notifyAll();
                        }
                    }
                }
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                if (!released) {
                    setExceptionLocked(new CineFfmpegDecoderException(
                            "FFmpeg video decode thread was interrupted.",
                            interruptedException
                    ));
                }
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            setException(new CineFfmpegDecoderException(
                    "Unexpected FFmpeg video decoder error.",
                    error
            ));
        } catch (CineFfmpegDecoderException decoderException) {
            setException(decoderException);
        }
    }

    private void processCompressedInput(DecoderInputBuffer inputBuffer, int generation)
            throws InterruptedException, CineFfmpegDecoderException {
        ByteBuffer inputData = inputBuffer.data;
        if (inputData == null) {
            throw new CineFfmpegDecoderException("FFmpeg compressed input buffer has no data.");
        }

        boolean firstCall = true;
        while (isGenerationCurrent(generation)) {
            VideoDecoderOutputBuffer outputBuffer = acquireOutputBuffer(generation);
            if (outputBuffer == null) {
                return;
            }

            int result;
            long context = nativeContext;
            if (context == 0L) {
                recycleUnusedOutputBuffer(outputBuffer);
                return;
            }
            if (firstCall) {
                result = nativeDecodePacket(
                        context,
                        inputData,
                        inputData.position(),
                        inputData.remaining(),
                        inputBuffer.timeUs,
                        outputMode,
                        outputBuffer,
                        false
                );
                firstCall = false;
            } else {
                result = nativeDecodePacket(
                        context,
                        null,
                        0,
                        0,
                        inputBuffer.timeUs,
                        outputMode,
                        outputBuffer,
                        false
                );
            }

            if (!isGenerationCurrent(generation)) {
                recycleUnusedOutputBuffer(outputBuffer);
                return;
            }

            if (result == NATIVE_RESULT_FRAME) {
                outputBuffer.format = format;
                publishDecodedFrame(outputBuffer, generation);
                continue;
            }

            recycleUnusedOutputBuffer(outputBuffer);
            if (result == NATIVE_RESULT_NO_FRAME) {
                return;
            }
            if (result == NATIVE_RESULT_END_OF_STREAM) {
                throw new CineFfmpegDecoderException(
                        "FFmpeg reached EOS while decoding a normal video packet."
                );
            }
            throw new CineFfmpegDecoderException("FFmpeg failed while decoding " + codecName + ".");
        }
    }

    private void processEndOfStream(DecoderInputBuffer inputBuffer, int generation)
            throws InterruptedException, CineFfmpegDecoderException {
        if (!isGenerationCurrent(generation)) {
            return;
        }

        long context = nativeContext;
        if (context == 0L) {
            return;
        }
        int sendResult = nativeSendEndOfStream(context);
        if (sendResult == NATIVE_RESULT_NO_FRAME) {
            throw new CineFfmpegDecoderException(
                    "FFmpeg requested more draining before EOS after its packet queue was empty."
            );
        }
        if (sendResult == NATIVE_RESULT_ERROR) {
            throw new CineFfmpegDecoderException("Unable to signal FFmpeg video EOS.");
        }

        if (sendResult == NATIVE_RESULT_END_OF_STREAM) {
            VideoDecoderOutputBuffer eosBuffer = acquireOutputBuffer(generation);
            if (eosBuffer != null) {
                publishEndOfStream(eosBuffer, inputBuffer.timeUs, generation);
            }
            return;
        }

        while (isGenerationCurrent(generation)) {
            VideoDecoderOutputBuffer outputBuffer = acquireOutputBuffer(generation);
            if (outputBuffer == null) {
                return;
            }

            int result = nativeDecodePacket(
                    context,
                    null,
                    0,
                    0,
                    inputBuffer.timeUs,
                    outputMode,
                    outputBuffer,
                    false
            );
            if (!isGenerationCurrent(generation)) {
                recycleUnusedOutputBuffer(outputBuffer);
                return;
            }

            if (result == NATIVE_RESULT_FRAME) {
                outputBuffer.format = format;
                publishDecodedFrame(outputBuffer, generation);
                continue;
            }
            if (result == NATIVE_RESULT_END_OF_STREAM) {
                publishEndOfStream(outputBuffer, inputBuffer.timeUs, generation);
                return;
            }

            recycleUnusedOutputBuffer(outputBuffer);
            if (result == NATIVE_RESULT_NO_FRAME) {
                throw new CineFfmpegDecoderException(
                        "FFmpeg EOS drain stalled before AVERROR_EOF."
                );
            }
            throw new CineFfmpegDecoderException("FFmpeg failed while draining delayed video frames.");
        }
    }

    @Nullable
    private VideoDecoderOutputBuffer acquireOutputBuffer(int generation)
            throws InterruptedException {
        synchronized (lock) {
            while (!released
                    && generation == flushGeneration
                    && availableOutputBuffers.isEmpty()) {
                lock.wait();
            }
            if (released || generation != flushGeneration) {
                return null;
            }
            VideoDecoderOutputBuffer outputBuffer = availableOutputBuffers.removeFirst();
            outputBuffer.clear();
            outputBuffer.decoderPrivate = 0L;
            return outputBuffer;
        }
    }

    private void publishDecodedFrame(VideoDecoderOutputBuffer outputBuffer, int generation) {
        synchronized (lock) {
            if (released || generation != flushGeneration) {
                recycleOutputBufferLocked(outputBuffer);
                lock.notifyAll();
                return;
            }

            if (outputStartTimeUs != C.TIME_UNSET && outputBuffer.timeUs < outputStartTimeUs) {
                skippedOutputBufferCount++;
                recycleOutputBufferLocked(outputBuffer);
                lock.notifyAll();
                return;
            }

            outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
            skippedOutputBufferCount = 0;
            queuedOutputBuffers.addLast(outputBuffer);
            lock.notifyAll();
        }
    }

    private void publishEndOfStream(
            VideoDecoderOutputBuffer outputBuffer,
            long timeUs,
            int generation
    ) {
        synchronized (lock) {
            if (released || generation != flushGeneration) {
                recycleOutputBufferLocked(outputBuffer);
                lock.notifyAll();
                return;
            }
            releaseNativeFrameLocked(outputBuffer);
            outputBuffer.clear();
            outputBuffer.timeUs = timeUs;
            outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
            skippedOutputBufferCount = 0;
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
            queuedOutputBuffers.addLast(outputBuffer);
            lock.notifyAll();
        }
    }

    private boolean isGenerationCurrent(int generation) {
        synchronized (lock) {
            return !released && generation == flushGeneration;
        }
    }

    private void recycleUnusedOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        synchronized (lock) {
            recycleOutputBufferLocked(outputBuffer);
            lock.notifyAll();
        }
    }

    private void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        synchronized (lock) {
            releaseNativeFrameLocked(outputBuffer);
            outputBuffer.clear();
            if (!released) {
                availableOutputBuffers.addLast(outputBuffer);
                lock.notifyAll();
            }
        }
    }

    private void recycleInputBufferLocked(DecoderInputBuffer inputBuffer) {
        inputBuffer.clear();
        availableInputBuffers.addLast(inputBuffer);
    }

    private void recycleOutputBufferLocked(VideoDecoderOutputBuffer outputBuffer) {
        releaseNativeFrameLocked(outputBuffer);
        outputBuffer.clear();
        outputBuffer.decoderPrivate = 0L;
        if (!released) {
            availableOutputBuffers.addLast(outputBuffer);
        }
    }

    private void releaseNativeFrameLocked(VideoDecoderOutputBuffer outputBuffer) {
        long frame = outputBuffer.decoderPrivate;
        if (frame == 0L) {
            return;
        }
        long context = nativeContext;
        if (context != 0L) {
            nativeReleaseFrame(context, frame);
        }
        outputBuffer.decoderPrivate = 0L;
    }

    private void maybeThrowException() throws CineFfmpegDecoderException {
        if (exception != null) {
            throw exception;
        }
    }

    private void setException(CineFfmpegDecoderException decoderException) {
        synchronized (lock) {
            setExceptionLocked(decoderException);
        }
    }

    private void setExceptionLocked(CineFfmpegDecoderException decoderException) {
        if (exception == null) {
            exception = decoderException;
        }
        lock.notifyAll();
    }

    private static native long nativeInitialize(
            String codecName,
            @Nullable byte[] extraData,
            int threads,
            int rotationDegrees,
            int width,
            int height,
            boolean discardNonReferenceFrames
    );

    private static native int nativeDecodePacket(
            long context,
            @Nullable ByteBuffer encodedData,
            int offset,
            int length,
            long presentationTimeUs,
            int outputMode,
            VideoDecoderOutputBuffer outputBuffer,
            boolean decodeOnly
    );

    private static native int nativeSendEndOfStream(long context);

    private static native int nativeFlush(long context);

    private static native int nativeRenderFrame(
            long context,
            long frame,
            Surface surface,
            long presentationTimeUs,
            int outputWidth,
            int outputHeight
    );

    private static native void nativeReleaseFrame(long context, long frame);

    private static native void nativeRelease(long context);
}
