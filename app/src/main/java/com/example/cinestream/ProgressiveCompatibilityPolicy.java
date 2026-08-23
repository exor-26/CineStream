package com.example.cinestream;

/** Deterministic segment and adaptation policy for future progressive compatibility playback. */
final class ProgressiveCompatibilityPolicy {
    static final long FIRST_SEGMENT_MS = 3_000L;
    static final long LATER_SEGMENT_MS = 5_000L;
    static final long INTERNAL_FRAGMENT_MS = 1_000L;
    static final long INITIAL_BUFFER_MS = 8_000L;
    static final int INITIAL_BUFFER_SEGMENTS = 2;

    static final class SegmentWindow {
        final int index;
        final long startMs;
        final long endMs;

        SegmentWindow(int index, long startMs, long endMs) {
            this.index = index;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        long durationMs() {
            return Math.max(0L, endMs - startMs);
        }
    }

    static final class Adaptation {
        final VideoResourceGovernor.Tier tier;
        final boolean sustainable;
        final int consecutiveFastSegments;

        Adaptation(
                VideoResourceGovernor.Tier tier,
                boolean sustainable,
                int consecutiveFastSegments
        ) {
            this.tier = tier;
            this.sustainable = sustainable;
            this.consecutiveFastSegments = consecutiveFastSegments;
        }
    }

    private ProgressiveCompatibilityPolicy() {
    }

    static SegmentWindow segmentWindow(int index, long sourceDurationMs) {
        int safeIndex = Math.max(0, index);
        long startMs = safeIndex == 0
                ? 0L
                : FIRST_SEGMENT_MS + (safeIndex - 1L) * LATER_SEGMENT_MS;
        long requestedDurationMs = safeIndex == 0 ? FIRST_SEGMENT_MS : LATER_SEGMENT_MS;
        long endMs = startMs + requestedDurationMs;
        if (sourceDurationMs > 0L) {
            endMs = Math.min(endMs, sourceDurationMs);
            startMs = Math.min(startMs, endMs);
        }
        return new SegmentWindow(safeIndex, startMs, endMs);
    }

    static int segmentIndexForPosition(long positionMs) {
        long safePositionMs = Math.max(0L, positionMs);
        if (safePositionMs < FIRST_SEGMENT_MS) {
            return 0;
        }
        return 1 + (int) ((safePositionMs - FIRST_SEGMENT_MS) / LATER_SEGMENT_MS);
    }

    static boolean hasInitialBuffer(
            int completedSegments,
            long bufferedDurationMs,
            boolean sourceEnded
    ) {
        if (sourceEnded) {
            return completedSegments > 0 && bufferedDurationMs > 0L;
        }
        return completedSegments >= INITIAL_BUFFER_SEGMENTS
                && bufferedDurationMs >= INITIAL_BUFFER_MS;
    }

    /**
     * Adapts from measured media-duration/export-duration ratio. Values above 1 are faster than
     * realtime. The first measured 480p segment is always allowed to finish before giving up.
     */
    static Adaptation adapt(
            VideoResourceGovernor.Tier current,
            VideoResourceGovernor.Tier governorCeiling,
            double generationRatio,
            int consecutiveFastSegments,
            boolean currentTierMeasured
    ) {
        VideoResourceGovernor.Tier safeCeiling = governorCeiling != null
                ? governorCeiling : VideoResourceGovernor.Tier.P480_24;
        VideoResourceGovernor.Tier safeCurrent = current != null
                ? lowerOf(current, safeCeiling) : safeCeiling;

        if (generationRatio < 0.55d) {
            if (safeCurrent == VideoResourceGovernor.Tier.P480_24) {
                return currentTierMeasured
                        ? new Adaptation(safeCurrent, false, 0)
                        : new Adaptation(safeCurrent, true, 0);
            }
            return new Adaptation(degrade(safeCurrent, 2), true, 0);
        }
        if (generationRatio < 0.90d) {
            if (safeCurrent == VideoResourceGovernor.Tier.P480_24) {
                return currentTierMeasured
                        ? new Adaptation(safeCurrent, false, 0)
                        : new Adaptation(safeCurrent, true, 0);
            }
            return new Adaptation(degrade(safeCurrent, 1), true, 0);
        }

        int fastCount = generationRatio >= 1.45d ? consecutiveFastSegments + 1 : 0;
        if (fastCount >= 2) {
            return new Adaptation(improve(safeCurrent, safeCeiling), true, 0);
        }
        return new Adaptation(safeCurrent, true, fastCount);
    }

    private static VideoResourceGovernor.Tier lowerOf(
            VideoResourceGovernor.Tier current,
            VideoResourceGovernor.Tier ceiling
    ) {
        return current.ordinal() >= ceiling.ordinal() ? current : ceiling;
    }

    private static VideoResourceGovernor.Tier degrade(
            VideoResourceGovernor.Tier tier,
            int steps
    ) {
        VideoResourceGovernor.Tier[] tiers = VideoResourceGovernor.Tier.values();
        return tiers[Math.min(tiers.length - 1, tier.ordinal() + Math.max(1, steps))];
    }

    private static VideoResourceGovernor.Tier improve(
            VideoResourceGovernor.Tier tier,
            VideoResourceGovernor.Tier ceiling
    ) {
        int improved = Math.max(ceiling.ordinal(), tier.ordinal() - 1);
        return VideoResourceGovernor.Tier.values()[improved];
    }
}
