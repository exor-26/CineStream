package com.example.cinestream;

import java.util.Locale;

final class PlayerGestureMath {

    static final float MIN_ZOOM = 0.85f;
    static final float FALLBACK_MAX_ZOOM = 2.75f;
    private static final float MIN_MAX_ZOOM = 1.25f;
    private static final float MAX_RENDER_MULTIPLIER = 2.75f;
    private static final long MIN_SEEK_RANGE_MS = 15_000L;
    private static final long MAX_SEEK_RANGE_MS = 180_000L;
    private static final float SEEK_DURATION_FRACTION = 0.12f;

    private PlayerGestureMath() {}

    static float clampZoom(
            float requestedZoom,
            int surfaceWidth,
            int surfaceHeight,
            int containerWidth,
            int containerHeight
    ) {
        float maxZoom = maxZoom(
                surfaceWidth,
                surfaceHeight,
                containerWidth,
                containerHeight
        );
        return Math.max(MIN_ZOOM, Math.min(maxZoom, requestedZoom));
    }

    static float maxZoom(
            int surfaceWidth,
            int surfaceHeight,
            int containerWidth,
            int containerHeight
    ) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0
                || containerWidth <= 0 || containerHeight <= 0) {
            return FALLBACK_MAX_ZOOM;
        }
        float widthCoverage = surfaceWidth / (float) containerWidth;
        float heightCoverage = surfaceHeight / (float) containerHeight;
        float coverage = Math.max(widthCoverage, heightCoverage);
        if (coverage <= 0f || !Float.isFinite(coverage)) {
            return FALLBACK_MAX_ZOOM;
        }
        float bounded = MAX_RENDER_MULTIPLIER / coverage;
        return Math.max(MIN_MAX_ZOOM, Math.min(FALLBACK_MAX_ZOOM, bounded));
    }

    static int zoomPercentage(float zoom) {
        return Math.round(Math.max(0f, zoom) * 100f);
    }

    static long mapSeekTarget(
            long startPositionMs,
            long durationMs,
            float horizontalDistancePx,
            float containerWidthPx
    ) {
        if (durationMs <= 0L || containerWidthPx <= 0f) {
            return Math.max(0L, startPositionMs);
        }
        long rangeMs = seekRangeMs(durationMs);
        double fraction = horizontalDistancePx / (double) containerWidthPx;
        long deltaMs = Math.round(fraction * rangeMs);
        return clampPosition(startPositionMs + deltaMs, durationMs);
    }

    static long seekRangeMs(long durationMs) {
        if (durationMs <= 0L) {
            return 0L;
        }
        long proportional = Math.round(durationMs * SEEK_DURATION_FRACTION);
        long bounded = Math.max(MIN_SEEK_RANGE_MS, Math.min(MAX_SEEK_RANGE_MS, proportional));
        return Math.min(durationMs, bounded);
    }

    static long clampPosition(long positionMs, long durationMs) {
        if (durationMs <= 0L) {
            return Math.max(0L, positionMs);
        }
        return Math.max(0L, Math.min(durationMs, positionMs));
    }

    static long logicalPosition(long[] windowDurationsMs, int currentWindowOffset, long positionInWindowMs) {
        if (windowDurationsMs == null || windowDurationsMs.length == 0) {
            return Math.max(0L, positionInWindowMs);
        }
        int safeOffset = Math.max(0, Math.min(currentWindowOffset, windowDurationsMs.length - 1));
        long logical = 0L;
        for (int i = 0; i < safeOffset; i++) {
            logical += Math.max(0L, windowDurationsMs[i]);
        }
        long currentDuration = Math.max(0L, windowDurationsMs[safeOffset]);
        logical += currentDuration > 0L
                ? Math.min(Math.max(0L, positionInWindowMs), currentDuration)
                : Math.max(0L, positionInWindowMs);
        return logical;
    }

    static long logicalDuration(long[] windowDurationsMs) {
        if (windowDurationsMs == null) {
            return 0L;
        }
        long total = 0L;
        for (long durationMs : windowDurationsMs) {
            if (durationMs <= 0L) {
                return 0L;
            }
            total += durationMs;
        }
        return total;
    }

    static SeekWindow resolveSeekWindow(long[] windowDurationsMs, long targetMs) {
        if (windowDurationsMs == null || windowDurationsMs.length == 0) {
            return new SeekWindow(0, Math.max(0L, targetMs));
        }
        long total = logicalDuration(windowDurationsMs);
        long remaining = total > 0L
                ? clampPosition(targetMs, total)
                : Math.max(0L, targetMs);
        for (int i = 0; i < windowDurationsMs.length; i++) {
            long duration = Math.max(0L, windowDurationsMs[i]);
            boolean last = i == windowDurationsMs.length - 1;
            if (last || remaining < duration) {
                long safePosition = duration > 0L
                        ? Math.min(remaining, duration)
                        : remaining;
                return new SeekWindow(i, Math.max(0L, safePosition));
            }
            remaining -= duration;
        }
        return new SeekWindow(windowDurationsMs.length - 1, 0L);
    }

    static String formatTimestamp(long positionMs) {
        long totalSeconds = Math.max(0L, positionMs) / 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    static final class SeekWindow {
        final int windowOffset;
        final long positionInWindowMs;

        SeekWindow(int windowOffset, long positionInWindowMs) {
            this.windowOffset = windowOffset;
            this.positionInWindowMs = positionInWindowMs;
        }
    }
}
