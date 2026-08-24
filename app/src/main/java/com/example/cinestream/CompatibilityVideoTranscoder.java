package com.example.cinestream;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.example.cinestream.ffmpeg.CineFfmpegLibrary;
import com.example.cinestream.ffmpeg.CineFfmpegTransformerDecoderFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates and caches a video-only H.264 compatibility rendition.
 *
 * The original file remains the source of audio and subtitle tracks during playback. This keeps
 * compatibility conversion focused on the expensive/incompatible video stream and avoids losing
 * multi-audio or embedded caption metadata.
 */
@UnstableApi
final class CompatibilityVideoTranscoder {
    private static final String TAG = "CompatVideoTranscoder";
    private static final long MAX_CACHE_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final long UNKNOWN_DURATION_ESTIMATE_BYTES = 128L * 1024L * 1024L;

    interface Callback {
        void onReady(File file, CompatibilityVideoPolicy.Target target, boolean fromCache);
        void onError(String message, Throwable error);
    }

    static final class Session {
        private Transformer transformer;
        private File outputFile;
        private boolean finished;

        synchronized boolean setActive(Transformer transformer, File outputFile) {
            if (finished) {
                return false;
            }
            this.transformer = transformer;
            this.outputFile = outputFile;
            return true;
        }

        synchronized boolean isFinished() {
            return finished;
        }

        synchronized void markFinished() {
            finished = true;
            transformer = null;
            outputFile = null;
        }

        void cancel() {
            Transformer activeTransformer;
            File activeOutput;
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                activeTransformer = transformer;
                activeOutput = outputFile;
                transformer = null;
                outputFile = null;
            }
            if (activeTransformer != null) {
                try {
                    activeTransformer.cancel();
                } catch (Exception ignored) {
                }
            }
            deleteQuietly(activeOutput);
        }
    }

    private static final class AttemptRunner {
        private final Context context;
        private final Uri sourceUri;
        private final float sourceFrameRate;
        private final Callback callback;
        private final File cacheDir;
        private final String sourceKey;
        private final List<CompatibilityVideoPolicy.Target> targets;
        private final Session session;
        private final long durationMs;

        private int nextTargetIndex;
        private Throwable lastError;
        private boolean forceFfmpegDecoder;

        AttemptRunner(
                Context context,
                Uri sourceUri,
                float sourceFrameRate,
                Callback callback,
                File cacheDir,
                String sourceKey,
                List<CompatibilityVideoPolicy.Target> targets,
                Session session,
                long durationMs,
                boolean forceFfmpegDecoder
        ) {
            this.context = context;
            this.sourceUri = sourceUri;
            this.sourceFrameRate = sourceFrameRate;
            this.callback = callback;
            this.cacheDir = cacheDir;
            this.sourceKey = sourceKey;
            this.targets = targets;
            this.session = session;
            this.durationMs = durationMs;
            this.forceFfmpegDecoder = forceFfmpegDecoder;
        }

        void startNext() {
            if (session.isFinished()) {
                return;
            }
            if (nextTargetIndex >= targets.size()) {
                session.markFinished();
                callback.onError(
                        "Compatibility export failed for every supported quality target.",
                        lastError
                );
                return;
            }

            CompatibilityVideoPolicy.Target target = targets.get(nextTargetIndex++);
            File outputFile = outputFile(cacheDir, sourceKey, target);
            if (isUsableVideo(outputFile, durationMs)) {
                touch(outputFile);
                session.markFinished();
                callback.onReady(outputFile, target, true);
                return;
            }
            deleteQuietly(outputFile);

            long requiredBytes = estimateRequiredBytes(target, durationMs);
            long usableBytes = cacheDir.getUsableSpace();
            if (usableBytes > 0
                    && usableBytes < requiredBytes + MIN_FREE_SPACE_RESERVE_BYTES) {
                lastError = new IllegalStateException(
                        "Insufficient free space for compatibility target " + target
                );
                Log.w(TAG, lastError.getMessage());
                startNext();
                return;
            }

            ArrayList<Effect> videoEffects = new ArrayList<>();
            if (sourceFrameRate > 0f && target.frameRate > 0f
                    && sourceFrameRate > target.frameRate + 1f) {
                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(target.frameRate));
            }
            videoEffects.add(Presentation.createForWidthAndHeight(
                    target.width,
                    target.height,
                    Presentation.LAYOUT_SCALE_TO_FIT
            ));

            EditedMediaItem editedMediaItem =
                    new EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                            .setRemoveAudio(true)
                            .setEffects(new Effects(Collections.emptyList(), videoEffects))
                            .build();
            Composition composition = new Composition.Builder(
                    EditedMediaItemSequence.withVideoFrom(
                            Collections.singletonList(editedMediaItem)
                    )
            )
                    // H.264 compatibility outputs are SDR. OpenGL tone mapping is preferred by
                    // Media3 and does not change SDR sources.
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build();

            DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context)
                    .setEnableDecoderFallback(true)
                    .setShouldConfigureOperatingRate(false)
                    .build();
            CineFfmpegTransformerDecoderFactory fallbackDecoderFactory =
                    new CineFfmpegTransformerDecoderFactory(
                            decoderFactory,
                            forceFfmpegDecoder,
                            target.width,
                            target.height,
                            target.frameRate
                    );
            DefaultAssetLoaderFactory assetLoaderFactory = new DefaultAssetLoaderFactory(
                    context,
                    fallbackDecoderFactory,
                    Clock.DEFAULT,
                    null
            );

            Transformer transformer = new Transformer.Builder(context)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(
                                Composition composition,
                                ExportResult exportResult
                        ) {
                            if (session.isFinished()) {
                                deleteQuietly(outputFile);
                                return;
                            }
                            if (isUsableVideo(outputFile, durationMs)) {
                                touch(outputFile);
                                session.markFinished();
                                callback.onReady(outputFile, target, false);
                                return;
                            }
                            deleteQuietly(outputFile);
                            lastError = new IllegalStateException(
                                    "Compatibility target produced no usable H.264 video: " + target
                            );
                            Log.w(TAG, lastError.getMessage());
                            startNext();
                        }

                        @Override
                        public void onError(
                                Composition composition,
                                ExportResult exportResult,
                                ExportException exportException
                        ) {
                            if (session.isFinished()) {
                                deleteQuietly(outputFile);
                                return;
                            }
                            // Transformer can report a player-release timeout after the muxer has
                            // already finalized a complete file. Preserve that valid output instead
                            // of deleting it and leaving playback with no video.
                            if (isUsableVideo(outputFile, durationMs)) {
                                Log.w(TAG, "Using completed compatibility output after exporter cleanup error");
                                touch(outputFile);
                                session.markFinished();
                                callback.onReady(outputFile, target, false);
                                return;
                            }
                            deleteQuietly(outputFile);
                            lastError = exportException;
                            Log.w(TAG, "Compatibility target failed: " + target, exportException);
                            if (!forceFfmpegDecoder
                                    && isDecoderFailure(exportException)) {
                                forceFfmpegDecoder = true;
                                CompatibilityVideoPolicy.Target nextTarget =
                                        nextTargetIndex < targets.size()
                                                ? targets.get(nextTargetIndex)
                                                : null;
                                if (!CompatibilityVideoPolicy.isLowerFrameRateVariant(
                                        target,
                                        nextTarget
                                )) {
                                    nextTargetIndex--;
                                }
                            }
                            startNext();
                        }
                    })
                    .build();

            if (!session.setActive(transformer, outputFile)) {
                return;
            }
            try {
                Log.i(TAG, "Trying H.264 compatibility target " + target);
                transformer.start(composition, outputFile.getAbsolutePath());
            } catch (RuntimeException error) {
                deleteQuietly(outputFile);
                lastError = error;
                Log.w(TAG, "Unable to start compatibility target " + target, error);
                startNext();
            }
        }
    }

    private CompatibilityVideoTranscoder() {}

    static Session start(
            Context context,
            Uri sourceUri,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            Callback callback
    ) {
        return start(
                context,
                sourceUri,
                sourceWidth,
                sourceHeight,
                sourceFrameRate,
                null,
                null,
                null,
                callback
        );
    }

    static Session start(
            Context context,
            Uri sourceUri,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            CompatibilityVideoPolicy.Target ceiling,
            Callback callback
    ) {
        return start(
                context,
                sourceUri,
                sourceWidth,
                sourceHeight,
                sourceFrameRate,
                null,
                null,
                ceiling,
                callback
        );
    }

    static Session start(
            Context context,
            Uri sourceUri,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            @Nullable String sourceMimeType,
            @Nullable String sourceCodecs,
            CompatibilityVideoPolicy.Target ceiling,
            Callback callback
    ) {
        List<CompatibilityVideoPolicy.Target> targets =
                DeviceVideoCapabilities.chooseH264CompatibilityTargets(
                        sourceWidth,
                        sourceHeight,
                        sourceFrameRate
                );
        if (ceiling != null) {
            targets.removeIf(target -> !CompatibilityVideoPolicy.isWithinCeiling(
                    target,
                    ceiling
            ));
        }
        if (targets.isEmpty()) {
            callback.onError(
                    "No device-supported H.264 compatibility target was found.",
                    null
            );
            return null;
        }

        File cacheDir = chooseCacheDir(context);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            callback.onError("Unable to create compatibility cache.", null);
            return null;
        }
        cleanupOldFiles(cacheDir);

        String sourceKey = sourceKey(context, sourceUri);
        long durationMs = readDurationMs(context, sourceUri);
        for (CompatibilityVideoPolicy.Target target : targets) {
            File cached = outputFile(cacheDir, sourceKey, target);
            if (isUsableVideo(cached, durationMs)) {
                touch(cached);
                callback.onReady(cached, target, true);
                return null;
            }
        }

        Session session = new Session();
        new AttemptRunner(
                context,
                sourceUri,
                sourceFrameRate,
                callback,
                cacheDir,
                sourceKey,
                targets,
                session,
                durationMs,
                CineFfmpegLibrary.isDolbyVisionFormat(sourceMimeType, sourceCodecs)
        ).startNext();
        return session.isFinished() ? null : session;
    }

    private static File chooseCacheDir(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getCacheDir();
        }
        return new File(base, "compatible_video");
    }

    private static File outputFile(
            File cacheDir,
            String sourceKey,
            CompatibilityVideoPolicy.Target target
    ) {
        return new File(
                cacheDir,
                "compat_" + sourceKey + "_"
                        + target.width + "x" + target.height + "_"
                        + Math.round(target.frameRate) + ".mp4"
        );
    }

    /**
     * Finds an already-created compatibility rendition that Android can safely thumbnail.
     * This does not start a conversion and must be called off the main thread.
     */
    static File findCachedVideoForThumbnail(Context context, Uri sourceUri) {
        return findCachedVideoForPlayback(context, sourceUri);
    }

    /** Returns a completed, reusable compatibility file without starting new work. */
    static File findCachedVideoForPlayback(Context context, Uri sourceUri) {
        File cacheDir = chooseCacheDir(context);
        String prefix = "compat_" + sourceKey(context, sourceUri) + "_";
        File[] files = cacheDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".mp4")
        );
        if (files == null || files.length == 0) {
            return null;
        }

        long expectedDurationMs = readDurationMs(context, sourceUri);
        File newestUsable = null;
        for (File file : files) {
            if (isUsableVideo(file, expectedDurationMs)
                    && (newestUsable == null
                    || file.lastModified() > newestUsable.lastModified())) {
                newestUsable = file;
            }
        }
        if (newestUsable != null) {
            touch(newestUsable);
        }
        return newestUsable;
    }

    private static void cleanupOldFiles(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() > 0 && file.lastModified() < cutoff) {
                deleteQuietly(file);
            }
        }
    }

    static String sourceKey(Context context, Uri uri) {
        long length = readSourceLength(context, uri);
        long modified = readSourceModifiedTime(context, uri);
        String material = uri + "|" + length + "|" + modified;
        return ProgressiveCompatibilityCache.stableSourceIdentity(material);
    }

    private static long readSourceLength(Context context, Uri uri) {
        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor != null ? descriptor.getLength() : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static long readSourceModifiedTime(Context context, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).lastModified();
        }
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    static long readDurationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            return duration != null ? Math.max(0L, Long.parseLong(duration)) : 0L;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    static long estimateRequiredBytes(
            CompatibilityVideoPolicy.Target target,
            long durationMs
    ) {
        if (durationMs <= 0L) {
            return UNKNOWN_DURATION_ESTIMATE_BYTES;
        }
        double frameRate = target.frameRate > 0f ? target.frameRate : 30d;
        double estimatedBitsPerSecond = target.width * (double) target.height
                * frameRate * 0.12d;
        long bitsPerSecond = Math.max(
                1_500_000L,
                Math.min(16_000_000L, Math.round(estimatedBitsPerSecond))
        );
        double encodedBytes = bitsPerSecond / 8d * (durationMs / 1000d);
        double withOverhead = encodedBytes * 1.2d + 16d * 1024d * 1024d;
        return withOverhead >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(withOverhead);
    }

    static boolean isDecoderFailure(ExportException error) {
        return error.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == ExportException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
    }

    static boolean isUsableVideo(File file, long expectedDurationMs) {
        if (file == null || !file.isFile() || file.length() < 4096L) {
            return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long actualDurationMs = parsePositiveLong(duration);
            boolean structurallyValid = parsePositive(width)
                    && parsePositive(height)
                    && isDurationComplete(expectedDurationMs, actualDurationMs)
                    && containsH264Video(file);
            if (!structurallyValid) {
                return false;
            }
            // Some low-end platform retrievers cannot extract a frame from an H.264 file that
            // their normal MediaCodec playback path can decode. A null thumbnail is therefore
            // inconclusive, not proof that the already-finalized video is black.
            if (!containsVisibleFrame(retriever, actualDurationMs)) {
                Log.w(TAG, "Platform frame probe unavailable; accepting structurally valid H.264");
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean containsVisibleFrame(
            MediaMetadataRetriever retriever,
            long durationMs
    ) {
        long[] sampleTimesUs = {
                durationMs * 250L,
                durationMs * 500L,
                durationMs * 750L
        };
        for (long sampleTimeUs : sampleTimesUs) {
            Bitmap frame = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    frame = retriever.getScaledFrameAtTime(
                            sampleTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            64,
                            36
                    );
                } else {
                    frame = retriever.getFrameAtTime(
                            sampleTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST
                    );
                }
                if (frame == null) {
                    continue;
                }
                int width = frame.getWidth();
                int height = frame.getHeight();
                int[] pixels = new int[width * height];
                frame.getPixels(pixels, 0, width, 0, 0, width, height);
                if (hasVisiblePixels(pixels)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Try another point before rejecting an otherwise valid cache entry.
            } finally {
                if (frame != null) {
                    frame.recycle();
                }
            }
        }
        return false;
    }

    static boolean hasVisiblePixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return false;
        }
        int requiredVisiblePixels = Math.max(1, pixels.length / 100);
        int visiblePixels = 0;
        for (int pixel : pixels) {
            int red = (pixel >>> 16) & 0xff;
            int green = (pixel >>> 8) & 0xff;
            int blue = pixel & 0xff;
            if (red >= 12 || green >= 12 || blue >= 12) {
                visiblePixels++;
                if (visiblePixels >= requiredVisiblePixels) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsH264Video(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (MimeTypes.VIDEO_H264.equals(mime)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            extractor.release();
        }
    }

    private static boolean parsePositive(String value) {
        return parsePositiveLong(value) > 0L;
    }

    private static long parsePositiveLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static boolean isDurationComplete(long expectedDurationMs, long actualDurationMs) {
        if (actualDurationMs <= 0L) {
            return false;
        }
        if (expectedDurationMs <= 0L) {
            return true;
        }
        long toleranceMs = Math.max(1_500L, expectedDurationMs / 20L);
        return actualDurationMs >= Math.max(1L, expectedDurationMs - toleranceMs);
    }

    private static void touch(File file) {
        if (file != null && file.exists() && !file.setLastModified(System.currentTimeMillis())) {
            Log.w(TAG, "Unable to refresh compatibility cache timestamp");
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete compatibility output " + file.getName());
        }
    }
}
