package com.example.cinestream;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.InAppFragmentedMp4Muxer;
import androidx.media3.transformer.Transformer;

import com.example.cinestream.ffmpeg.CineFfmpegLibrary;
import com.example.cinestream.ffmpeg.CineFfmpegTransformerDecoderFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** App-process progressive H.264 segment producer. Transformer access stays on the main looper. */
final class ProgressiveCompatibilityManager {
    private static final String TAG = "ProgressiveCompatibility";
    private static final long DETACHED_CANCEL_DELAY_MS = 15_000L;
    private static final long MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final Map<String, Job> JOBS = new HashMap<>();
    private static final Handler MAIN = AppExecutors.mainHandler();

    static final class Segment {
        final File file;
        final ProgressiveCompatibilityPolicy.SegmentWindow window;
        final CompatibilityVideoPolicy.Target target;

        Segment(
                File file,
                ProgressiveCompatibilityPolicy.SegmentWindow window,
                CompatibilityVideoPolicy.Target target
        ) {
            this.file = file;
            this.window = window;
            this.target = target;
        }
    }

    interface Listener {
        void onInitialSegments(List<Segment> segments, boolean entirelyFromCache);

        void onSegmentReady(Segment segment);

        void onCompleted(List<Segment> segments);

        void onFailed(String message, @Nullable Throwable error);
    }

    static final class Handle {
        @Nullable private final Job job;
        private final Listener listener;
        private boolean detached;

        Handle(@Nullable Job job, Listener listener) {
            this.job = job;
            this.listener = listener;
        }

        void detach() {
            if (detached) {
                return;
            }
            detached = true;
            if (job != null) {
                job.detach(listener);
            }
        }

        long generatedDurationMs() {
            return job != null ? job.generatedDurationMs() : 0L;
        }
    }

    private static final class Job {
        private final Context context;
        private final Uri sourceUri;
        private final String sourceKey;
        private final int sourceWidth;
        private final int sourceHeight;
        private final float sourceFrameRate;
        @Nullable private final String sourceMimeType;
        private final long sourceDurationMs;
        private final File cacheDir;
        private final List<CompatibilityVideoPolicy.Target> targets;
        private final VideoResourceGovernor.Tier governorCeiling;
        private final ProgressiveCompatibilitySessionRegistry.Lease lease;
        private final List<Listener> listeners = new ArrayList<>();
        private final List<Segment> segments = new ArrayList<>();

        private int targetIndex;
        private int nextSegmentIndex;
        private int consecutiveFastSegments;
        private boolean forceFfmpegDecoder;
        private boolean generatedAny;
        private boolean initialDelivered;
        private boolean finished;
        private boolean cancelled;
        @Nullable private Transformer transformer;
        @Nullable private File activeIncomplete;
        private final Runnable detachedCancellation = this::cancelIfStillDetached;

        Job(
                Context context,
                Uri sourceUri,
                String sourceKey,
                int sourceWidth,
                int sourceHeight,
                float sourceFrameRate,
                @Nullable String sourceMimeType,
                long sourceDurationMs,
                File cacheDir,
                List<CompatibilityVideoPolicy.Target> targets,
                VideoResourceGovernor.Tier governorCeiling
        ) {
            this.context = context.getApplicationContext();
            this.sourceUri = sourceUri;
            this.sourceKey = sourceKey;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.sourceFrameRate = sourceFrameRate;
            this.sourceMimeType = sourceMimeType;
            this.sourceDurationMs = sourceDurationMs;
            this.cacheDir = cacheDir;
            this.targets = targets;
            this.governorCeiling = governorCeiling;
            lease = ProgressiveCompatibilitySessionRegistry.acquireOrJoin(sourceKey);
        }

        void attach(Listener listener) {
            MAIN.removeCallbacks(detachedCancellation);
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
            if (initialDelivered) {
                listener.onInitialSegments(copySegments(), !generatedAny);
            }
            if (finished) {
                listener.onCompleted(copySegments());
            }
        }

        void detach(Listener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty() && !finished && !cancelled) {
                MAIN.postDelayed(detachedCancellation, DETACHED_CANCEL_DELAY_MS);
            }
        }

        void start() {
            AppExecutors.mediaIo().execute(() -> {
                List<Segment> cached = discoverCachedPrefix();
                MAIN.post(() -> {
                    if (cancelled) {
                        return;
                    }
                    segments.addAll(cached);
                    nextSegmentIndex = segments.size();
                    maybeDeliverInitial(false);
                    if (reachedSourceEnd()) {
                        complete();
                    } else {
                        startNextSegment();
                    }
                });
            });
        }

        long generatedDurationMs() {
            if (segments.isEmpty()) {
                return 0L;
            }
            return segments.get(segments.size() - 1).window.endMs;
        }

        private List<Segment> discoverCachedPrefix() {
            List<Segment> cached = new ArrayList<>();
            for (int index = 0; ; index++) {
                ProgressiveCompatibilityPolicy.SegmentWindow window =
                        ProgressiveCompatibilityPolicy.segmentWindow(index, sourceDurationMs);
                if (window.durationMs() <= 0L) {
                    break;
                }
                Segment found = null;
                for (CompatibilityVideoPolicy.Target target : targets) {
                    File file = ProgressiveCompatibilityCache.completedSegment(
                            cacheDir,
                            sourceKey,
                            window,
                            target
                    );
                    if (CompatibilityVideoTranscoder.isUsableVideo(
                            file,
                            window.durationMs()
                    )) {
                        found = new Segment(file, window, target);
                        break;
                    }
                }
                if (found == null) {
                    break;
                }
                cached.add(found);
                if (sourceDurationMs > 0L && window.endMs >= sourceDurationMs) {
                    break;
                }
            }
            return cached;
        }

        private void startNextSegment() {
            if (cancelled || finished || transformer != null) {
                return;
            }
            ProgressiveCompatibilityPolicy.SegmentWindow window =
                    ProgressiveCompatibilityPolicy.segmentWindow(
                            nextSegmentIndex,
                            sourceDurationMs
                    );
            if (window.durationMs() <= 0L) {
                complete();
                return;
            }
            CompatibilityVideoPolicy.Target target = targets.get(targetIndex);
            File completed = ProgressiveCompatibilityCache.completedSegment(
                    cacheDir,
                    sourceKey,
                    window,
                    target
            );
            if (CompatibilityVideoTranscoder.isUsableVideo(completed, window.durationMs())) {
                acceptSegment(new Segment(completed, window, target), 0L, true);
                return;
            }

            long requiredBytes = CompatibilityVideoTranscoder.estimateRequiredBytes(
                    target,
                    window.durationMs()
            );
            long usableBytes = cacheDir.getUsableSpace();
            if (usableBytes > 0L
                    && usableBytes < requiredBytes + MIN_FREE_SPACE_RESERVE_BYTES) {
                if (targetIndex + 1 < targets.size()) {
                    targetIndex++;
                    consecutiveFastSegments = 0;
                    startNextSegment();
                } else {
                    fail("Insufficient free space for progressive preparation.", null);
                }
                return;
            }

            File incomplete = ProgressiveCompatibilityCache.incompleteSegment(completed);
            ProgressiveCompatibilityCache.deleteIncomplete(incomplete);
            if (!lease.registerIncomplete(incomplete)) {
                fail("Progressive generation ownership changed.", null);
                return;
            }
            activeIncomplete = incomplete;
            long exportStartedMs = SystemClock.elapsedRealtime();

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
            MediaItem clippedItem = new MediaItem.Builder()
                    .setUri(sourceUri)
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(window.startMs)
                                    .setEndPositionMs(window.endMs)
                                    .build()
                    )
                    .build();
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(clippedItem)
                    .setRemoveAudio(true)
                    .setEffects(new Effects(Collections.emptyList(), videoEffects))
                    .build();
            Composition composition = new Composition.Builder(
                    EditedMediaItemSequence.withVideoFrom(
                            Collections.singletonList(editedMediaItem)
                    )
            )
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build();

            DefaultDecoderFactory platformDecoder = new DefaultDecoderFactory.Builder(context)
                    .setEnableDecoderFallback(true)
                    .setShouldConfigureOperatingRate(false)
                    .build();
            CineFfmpegTransformerDecoderFactory fallbackDecoder =
                    new CineFfmpegTransformerDecoderFactory(
                            platformDecoder,
                            forceFfmpegDecoder,
                            target.width,
                            target.height,
                            target.frameRate
                    );
            DefaultAssetLoaderFactory assetLoaderFactory = new DefaultAssetLoaderFactory(
                    context,
                    fallbackDecoder,
                    Clock.DEFAULT,
                    null
            );
            Transformer newTransformer = new Transformer.Builder(context)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setMuxerFactory(new InAppFragmentedMp4Muxer.Factory(
                            ProgressiveCompatibilityPolicy.INTERNAL_FRAGMENT_MS
                    ).setVideoDurationUs(window.durationMs() * 1_000L))
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(
                                Composition completedComposition,
                                ExportResult exportResult
                        ) {
                            transformer = null;
                            validateAndAccept(
                                    incomplete,
                                    completed,
                                    window,
                                    target,
                                    SystemClock.elapsedRealtime() - exportStartedMs
                            );
                        }

                        @Override
                        public void onError(
                                Composition failedComposition,
                                ExportResult exportResult,
                                ExportException exportException
                        ) {
                            transformer = null;
                            ProgressiveCompatibilityCache.deleteIncomplete(incomplete);
                            activeIncomplete = null;
                            handleExportFailure(exportException);
                        }
                    })
                    .build();
            transformer = newTransformer;
            try {
                newTransformer.start(composition, incomplete.getAbsolutePath());
            } catch (RuntimeException error) {
                transformer = null;
                ProgressiveCompatibilityCache.deleteIncomplete(incomplete);
                activeIncomplete = null;
                handleExportFailure(error);
            }
        }

        private void validateAndAccept(
                File incomplete,
                File completed,
                ProgressiveCompatibilityPolicy.SegmentWindow window,
                CompatibilityVideoPolicy.Target target,
                long exportElapsedMs
        ) {
            AppExecutors.mediaIo().execute(() -> {
                boolean valid = CompatibilityVideoTranscoder.isUsableVideo(
                        incomplete,
                        window.durationMs()
                );
                if (valid && completed.isFile()) {
                    if (CompatibilityVideoTranscoder.isUsableVideo(
                            completed,
                            window.durationMs()
                    )) {
                        ProgressiveCompatibilityCache.deleteIncomplete(incomplete);
                    } else if (ProgressiveCompatibilityCache.isCompletedSegment(completed)) {
                        //noinspection ResultOfMethodCallIgnored
                        completed.delete();
                    }
                }
                if (valid && !completed.exists()) {
                    valid = ProgressiveCompatibilityCache.promote(incomplete, completed);
                }
                if (valid) {
                    valid = CompatibilityVideoTranscoder.isUsableVideo(
                            completed,
                            window.durationMs()
                    );
                }
                boolean accepted = valid;
                MAIN.post(() -> {
                    activeIncomplete = null;
                    if (cancelled) {
                        return;
                    }
                    if (!accepted) {
                        ProgressiveCompatibilityCache.deleteIncomplete(incomplete);
                        handleExportFailure(new IllegalStateException(
                                "Progressive segment validation failed."
                        ));
                        return;
                    }
                    acceptSegment(
                            new Segment(completed, window, target),
                            exportElapsedMs,
                            false
                    );
                });
            });
        }

        private void acceptSegment(Segment segment, long exportElapsedMs, boolean fromCache) {
            if (cancelled || finished) {
                return;
            }
            segments.add(segment);
            nextSegmentIndex++;
            if (!fromCache) {
                generatedAny = true;
                forceFfmpegDecoder = false;
                double ratio = exportElapsedMs > 0L
                        ? (double) segment.window.durationMs() / exportElapsedMs
                        : Double.POSITIVE_INFINITY;
                ProgressiveCompatibilityPolicy.Adaptation adaptation =
                        ProgressiveCompatibilityPolicy.adapt(
                                tierForTarget(segment.target),
                                governorCeiling,
                                ratio,
                                consecutiveFastSegments,
                                true
                        );
                consecutiveFastSegments = adaptation.consecutiveFastSegments;
                if (!adaptation.sustainable) {
                    fail("Progressive preparation cannot stay ahead safely.", null);
                    return;
                }
                targetIndex = targetIndexForTier(adaptation.tier);
            }

            if (initialDelivered) {
                for (Listener listener : copyListeners()) {
                    listener.onSegmentReady(segment);
                }
            } else {
                maybeDeliverInitial(reachedSourceEnd());
            }
            if (reachedSourceEnd()) {
                complete();
            } else {
                startNextSegment();
            }
        }

        private void maybeDeliverInitial(boolean sourceEnded) {
            if (initialDelivered || !ProgressiveCompatibilityPolicy.hasInitialBuffer(
                    segments.size(),
                    generatedDurationMs(),
                    sourceEnded
            )) {
                return;
            }
            initialDelivered = true;
            List<Segment> snapshot = copySegments();
            for (Listener listener : copyListeners()) {
                listener.onInitialSegments(snapshot, !generatedAny);
            }
        }

        private void handleExportFailure(Throwable error) {
            if (cancelled || finished) {
                return;
            }
            if (error instanceof ExportException
                    && CompatibilityVideoTranscoder.isDecoderFailure((ExportException) error)
                    && !forceFfmpegDecoder
                    && CineFfmpegLibrary.supportsTransformerMimeType(
                    sourceMimeType)) {
                forceFfmpegDecoder = true;
                startNextSegment();
                return;
            }
            forceFfmpegDecoder = false;
            if (targetIndex + 1 < targets.size()) {
                targetIndex++;
                consecutiveFastSegments = 0;
                startNextSegment();
                return;
            }
            fail("Progressive preparation failed at every safe quality tier.", error);
        }

        private int targetIndexForTier(VideoResourceGovernor.Tier tier) {
            for (int index = 0; index < targets.size(); index++) {
                if (tierForTarget(targets.get(index)).ordinal() >= tier.ordinal()) {
                    return index;
                }
            }
            return targets.size() - 1;
        }

        private boolean reachedSourceEnd() {
            return sourceDurationMs > 0L && generatedDurationMs() >= sourceDurationMs;
        }

        private void complete() {
            if (finished || cancelled) {
                return;
            }
            maybeDeliverInitial(true);
            finished = true;
            lease.complete();
            List<Segment> snapshot = copySegments();
            for (Listener listener : copyListeners()) {
                listener.onCompleted(snapshot);
            }
            synchronized (ProgressiveCompatibilityManager.class) {
                JOBS.remove(sourceKey, this);
            }
        }

        private void fail(String message, @Nullable Throwable error) {
            if (finished || cancelled) {
                return;
            }
            cancelled = true;
            Transformer activeTransformer = transformer;
            transformer = null;
            if (activeTransformer != null) {
                try {
                    activeTransformer.cancel();
                } catch (RuntimeException ignored) {
                }
            }
            lease.cancel();
            ProgressiveCompatibilityCache.deleteIncomplete(activeIncomplete);
            activeIncomplete = null;
            for (Listener listener : copyListeners()) {
                listener.onFailed(message, error);
            }
            synchronized (ProgressiveCompatibilityManager.class) {
                JOBS.remove(sourceKey, this);
            }
        }

        private void cancelIfStillDetached() {
            if (listeners.isEmpty() && !finished && !cancelled) {
                fail("Progressive preparation was released.", null);
            }
        }

        private List<Segment> copySegments() {
            return Collections.unmodifiableList(new ArrayList<>(segments));
        }

        private List<Listener> copyListeners() {
            return new ArrayList<>(listeners);
        }
    }

    private ProgressiveCompatibilityManager() {
    }

    static synchronized Handle start(
            Context context,
            Uri sourceUri,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            @Nullable String sourceMimeType,
            @Nullable CompatibilityVideoPolicy.Target ceiling,
            Listener listener
    ) {
        String sourceKey = CompatibilityVideoTranscoder.sourceKey(context, sourceUri);
        Job existing = JOBS.get(sourceKey);
        if (existing != null) {
            existing.attach(listener);
            return new Handle(existing, listener);
        }

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
            MAIN.post(() -> listener.onFailed(
                    "No safe H.264 progressive target is available.",
                    null
            ));
            return new Handle(null, listener);
        }

        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getCacheDir();
        }
        File cacheDir = new File(base, "progressive_video");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            MAIN.post(() -> listener.onFailed("Unable to create progressive cache.", null));
            return new Handle(null, listener);
        }
        VideoResourceGovernor.Tier governorCeiling = ceiling != null
                ? tierForTarget(ceiling) : tierForTarget(targets.get(0));
        Job job = new Job(
                context,
                sourceUri,
                sourceKey,
                sourceWidth,
                sourceHeight,
                sourceFrameRate,
                sourceMimeType,
                CompatibilityVideoTranscoder.readDurationMs(context, sourceUri),
                cacheDir,
                targets,
                governorCeiling
        );
        JOBS.put(sourceKey, job);
        job.attach(listener);
        job.start();
        return new Handle(job, listener);
    }

    private static VideoResourceGovernor.Tier tierForTarget(
            CompatibilityVideoPolicy.Target target
    ) {
        int longEdge = Math.max(target.width, target.height);
        int shortEdge = Math.min(target.width, target.height);
        if (longEdge <= 900 || shortEdge <= 500 || target.frameRate <= 24.5f) {
            return VideoResourceGovernor.Tier.P480_24;
        }
        if (longEdge <= 1_300 || shortEdge <= 740) {
            return VideoResourceGovernor.Tier.P720_30;
        }
        return target.frameRate > 31f
                ? VideoResourceGovernor.Tier.P1080_60
                : VideoResourceGovernor.Tier.P1080_30;
    }

}
