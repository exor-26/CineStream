package com.example.cinestream;

import java.util.Locale;

/** Pure, device-neutral policy for direct software playback and compatibility quality ceilings. */
final class VideoResourceGovernor {
    static final long MIN_CPU_SAMPLE_MS = 250L;
    static final long SOFTWARE_STARTUP_GRACE_MS = 5_000L;
    static final long SOFTWARE_RECHECK_MS = 3_000L;

    enum Tier {
        P1080_60(1920, 1080, 60f),
        P1080_30(1920, 1080, 30f),
        P720_30(1280, 720, 30f),
        P480_24(854, 480, 24f);

        final int maxWidth;
        final int maxHeight;
        final float maxFrameRate;

        Tier(int maxWidth, int maxHeight, float maxFrameRate) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.maxFrameRate = maxFrameRate;
        }
    }

    static final class Snapshot {
        final long availableMemoryBytes;
        final long totalMemoryBytes;
        final long lowMemoryThresholdBytes;
        final boolean lowMemory;
        final int processorCount;
        final double processCpuLoad;
        final int thermalStatus;
        final int sourceWidth;
        final int sourceHeight;
        final float sourceFrameRate;
        final long sourceBitrate;
        final int sourceBitDepth;
        final int displayWidth;
        final int displayHeight;
        final float displayRefreshRate;
        final int reportedPerformance;
        final boolean hardwarePlaybackFailed;

        Snapshot(
                long availableMemoryBytes,
                long totalMemoryBytes,
                long lowMemoryThresholdBytes,
                boolean lowMemory,
                int processorCount,
                double processCpuLoad,
                int thermalStatus,
                int sourceWidth,
                int sourceHeight,
                float sourceFrameRate,
                long sourceBitrate,
                int sourceBitDepth,
                int displayWidth,
                int displayHeight,
                float displayRefreshRate,
                int reportedPerformance,
                boolean hardwarePlaybackFailed
        ) {
            this.availableMemoryBytes = Math.max(0L, availableMemoryBytes);
            this.totalMemoryBytes = Math.max(0L, totalMemoryBytes);
            this.lowMemoryThresholdBytes = Math.max(0L, lowMemoryThresholdBytes);
            this.lowMemory = lowMemory;
            this.processorCount = Math.max(1, processorCount);
            this.processCpuLoad = processCpuLoad;
            this.thermalStatus = Math.max(0, thermalStatus);
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.sourceFrameRate = sourceFrameRate;
            this.sourceBitrate = sourceBitrate;
            this.sourceBitDepth = Math.max(8, sourceBitDepth);
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.displayRefreshRate = displayRefreshRate;
            this.reportedPerformance = reportedPerformance;
            this.hardwarePlaybackFailed = hardwarePlaybackFailed;
        }
    }

    static final class Observation {
        final long elapsedMs;
        final long mediaProgressMs;
        final int renderedFrames;
        final int droppedFrames;
        final boolean startupComplete;

        Observation(long elapsedMs, long mediaProgressMs, int renderedFrames, int droppedFrames) {
            this(elapsedMs, mediaProgressMs, renderedFrames, droppedFrames, false);
        }

        Observation(
                long elapsedMs,
                long mediaProgressMs,
                int renderedFrames,
                int droppedFrames,
                boolean startupComplete
        ) {
            this.elapsedMs = Math.max(0L, elapsedMs);
            this.mediaProgressMs = Math.max(0L, mediaProgressMs);
            this.renderedFrames = Math.max(0, renderedFrames);
            this.droppedFrames = Math.max(0, droppedFrames);
            this.startupComplete = startupComplete;
        }
    }

    static final class Decision {
        final Tier ceiling;
        final boolean observationMature;
        final boolean directSoftwareSustainable;
        final String reason;

        Decision(
                Tier ceiling,
                boolean observationMature,
                boolean directSoftwareSustainable,
                String reason
        ) {
            this.ceiling = ceiling;
            this.observationMature = observationMature;
            this.directSoftwareSustainable = directSoftwareSustainable;
            this.reason = reason;
        }

        CompatibilityVideoPolicy.Target compatibilityCeiling(
                int sourceWidth,
                int sourceHeight,
                float sourceFrameRate
        ) {
            int maxWidth = sourceWidth >= sourceHeight ? ceiling.maxWidth : ceiling.maxHeight;
            int maxHeight = sourceWidth >= sourceHeight ? ceiling.maxHeight : ceiling.maxWidth;
            int[] size = CompatibilityVideoPolicy.fitWithin(
                    sourceWidth,
                    sourceHeight,
                    maxWidth,
                    maxHeight
            );
            float rate = sourceFrameRate > 0f
                    ? Math.min(sourceFrameRate, ceiling.maxFrameRate)
                    : ceiling.maxFrameRate;
            return new CompatibilityVideoPolicy.Target(size[0], size[1], rate);
        }
    }

    /** Recognizable internal signal for a Media3-wrapped governor handoff. */
    static final class HandoffException extends Exception {
        HandoffException(String message) {
            super(message);
        }
    }

    private VideoResourceGovernor() {
    }

    static Decision evaluate(Snapshot snapshot, Observation observation) {
        Tier ceiling = Tier.P1080_60;
        String reason = "runtime resources allow the highest compatibility tier";

        if (snapshot.displayRefreshRate > 0f && snapshot.displayRefreshRate < 50f) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "display refresh does not benefit from 60 fps output";
        }
        int displayLongEdge = Math.max(snapshot.displayWidth, snapshot.displayHeight);
        int displayShortEdge = Math.min(snapshot.displayWidth, snapshot.displayHeight);
        if (displayLongEdge > 0 && displayShortEdge > 0) {
            if (displayLongEdge < 1_000 || displayShortEdge < 600) {
                ceiling = lowerOf(ceiling, Tier.P480_24);
                reason = "display is bounded near the 480p tier";
            } else if (displayLongEdge < 1_600 || displayShortEdge < 900) {
                ceiling = lowerOf(ceiling, Tier.P720_30);
                reason = "display is bounded near the 720p tier";
            }
        }

        if (snapshot.processorCount <= 2) {
            ceiling = lowerOf(ceiling, Tier.P480_24);
            reason = "limited processor parallelism";
        } else if (snapshot.processorCount <= 4) {
            ceiling = lowerOf(ceiling, Tier.P720_30);
            reason = "moderate processor parallelism";
        } else if (snapshot.processorCount <= 6) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "processor count favors a 30 fps ceiling";
        }

        double freeFraction = snapshot.totalMemoryBytes > 0L
                ? (double) snapshot.availableMemoryBytes / snapshot.totalMemoryBytes
                : 1d;
        boolean belowSystemReserve = snapshot.lowMemoryThresholdBytes > 0L
                && snapshot.availableMemoryBytes <= snapshot.lowMemoryThresholdBytes;
        if (snapshot.lowMemory || belowSystemReserve || freeFraction < 0.10d) {
            ceiling = lowerOf(ceiling, Tier.P480_24);
            reason = "system memory is under pressure";
        } else if (freeFraction < 0.18d) {
            ceiling = lowerOf(ceiling, Tier.P720_30);
            reason = "available memory favors 720p";
        } else if (freeFraction < 0.28d) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "available memory favors 30 fps";
        }

        if (snapshot.thermalStatus >= 3) {
            ceiling = lowerOf(ceiling, Tier.P480_24);
            reason = "thermal status is severe";
        } else if (snapshot.thermalStatus >= 2) {
            ceiling = lowerOf(ceiling, Tier.P720_30);
            reason = "thermal status is moderate";
        } else if (snapshot.thermalStatus >= 1) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "thermal status is elevated";
        }

        if (isMeasured(snapshot.processCpuLoad)) {
            if (snapshot.processCpuLoad >= 0.90d) {
                ceiling = lowerOf(ceiling, Tier.P480_24);
                reason = "process CPU load is near saturation";
            } else if (snapshot.processCpuLoad >= 0.75d) {
                ceiling = lowerOf(ceiling, Tier.P720_30);
                reason = "process CPU load is high";
            } else if (snapshot.processCpuLoad >= 0.60d) {
                ceiling = lowerOf(ceiling, Tier.P1080_30);
                reason = "process CPU load favors 30 fps";
            }
        }

        long sourcePixels = safePixels(snapshot.sourceWidth, snapshot.sourceHeight);
        if (snapshot.hardwarePlaybackFailed) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "runtime hardware failure overrides the reported capability table";
        }
        if (sourcePixels > 4096L * 2160L) {
            ceiling = lowerOf(ceiling, Tier.P1080_30);
            reason = "source decode complexity exceeds 4K";
        }
        if (sourcePixels == Long.MAX_VALUE) {
            ceiling = lowerOf(ceiling, Tier.P720_30);
            reason = "unknown source dimensions require a conservative ceiling";
        }
        if ((snapshot.sourceBitDepth > 8 && sourcePixels > 1920L * 1080L)
                || snapshot.sourceBitrate > 80_000_000L
                || snapshot.reportedPerformance < 0) {
            ceiling = lowerOf(ceiling, Tier.P720_30);
            reason = "source complexity exceeds the reported realtime envelope";
        }

        boolean mature = observation != null
                && (observation.startupComplete
                || observation.elapsedMs >= SOFTWARE_STARTUP_GRACE_MS);
        if (!mature) {
            boolean immediateStop = snapshot.lowMemory || snapshot.thermalStatus >= 4;
            return new Decision(
                    ceiling,
                    false,
                    !immediateStop,
                    immediateStop ? "critical runtime pressure during startup" : reason
            );
        }

        double realtimeRatio = observation.elapsedMs > 0L
                ? (double) observation.mediaProgressMs / observation.elapsedMs
                : 0d;
        int observedFrames = observation.renderedFrames + observation.droppedFrames;
        double dropFraction = observedFrames > 0
                ? (double) observation.droppedFrames / observedFrames
                : 0d;
        boolean sustainable = !snapshot.lowMemory
                && snapshot.thermalStatus < 4
                && realtimeRatio >= 0.88d
                && dropFraction <= 0.18d;
        if (isMeasured(snapshot.processCpuLoad) && snapshot.processCpuLoad >= 0.98d
                && realtimeRatio < 0.97d) {
            sustainable = false;
        }

        String observationReason = String.format(
                Locale.US,
                "software ratio %.2f, dropped %.1f%%",
                realtimeRatio,
                dropFraction * 100d
        );
        return new Decision(ceiling, true, sustainable, observationReason);
    }

    static int estimateBitDepth(String codecString, boolean hdrTransfer) {
        if (hdrTransfer) {
            return 10;
        }
        if (codecString == null) {
            return 8;
        }
        String codec = codecString.toLowerCase(Locale.US);
        if (codec.contains("main10")
                || codec.contains("profile2")
                || codec.contains("10bit")
                || codec.matches(".*(?:hev1|hvc1)\\.2\\..*")) {
            return 10;
        }
        return 8;
    }

    private static boolean isMeasured(double value) {
        return !Double.isNaN(value) && value >= 0d;
    }

    private static long safePixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            return Long.MAX_VALUE;
        }
        return (long) width * height;
    }

    private static Tier lowerOf(Tier current, Tier candidate) {
        return current.ordinal() >= candidate.ordinal() ? current : candidate;
    }
}
