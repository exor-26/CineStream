package com.example.cinestream;

import androidx.media3.common.Player;

final class PictureInPicturePolicy {

    static final int MIN_SUPPORTED_SDK = 26;
    private static final int DEFAULT_WIDTH = 16;
    private static final int DEFAULT_HEIGHT = 9;
    private static final int MAX_RATIO_NUMERATOR = 239;
    private static final int MAX_RATIO_DENOMINATOR = 100;

    private PictureInPicturePolicy() {
    }

    static boolean shouldEnter(
            int sdkInt,
            boolean playWhenReady,
            int playbackState,
            boolean hasCurrentMediaItem
    ) {
        if (sdkInt < MIN_SUPPORTED_SDK || !playWhenReady || !hasCurrentMediaItem) {
            return false;
        }
        return playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY;
    }

    static int[] resolveAspectRatio(
            int width,
            int height,
            int rotationDegrees,
            float pixelWidthHeightRatio
    ) {
        if (width <= 0 || height <= 0) {
            return new int[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
        }

        float safePixelRatio = pixelWidthHeightRatio > 0f ? pixelWidthHeightRatio : 1f;
        long adjustedWidth = Math.max(1L, Math.round(width * safePixelRatio));
        long adjustedHeight = height;
        int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        if (normalizedRotation == 90 || normalizedRotation == 270) {
            long swapped = adjustedWidth;
            adjustedWidth = adjustedHeight;
            adjustedHeight = swapped;
        }

        double ratio = (double) adjustedWidth / (double) adjustedHeight;
        double maximumRatio = (double) MAX_RATIO_NUMERATOR / MAX_RATIO_DENOMINATOR;
        if (ratio > maximumRatio) {
            return new int[]{MAX_RATIO_NUMERATOR, MAX_RATIO_DENOMINATOR};
        }
        if (ratio < 1d / maximumRatio) {
            return new int[]{MAX_RATIO_DENOMINATOR, MAX_RATIO_NUMERATOR};
        }

        long divisor = greatestCommonDivisor(adjustedWidth, adjustedHeight);
        adjustedWidth /= divisor;
        adjustedHeight /= divisor;
        while (adjustedWidth > Integer.MAX_VALUE || adjustedHeight > Integer.MAX_VALUE) {
            adjustedWidth = Math.max(1L, adjustedWidth / 2L);
            adjustedHeight = Math.max(1L, adjustedHeight / 2L);
        }
        return new int[]{(int) adjustedWidth, (int) adjustedHeight};
    }

    private static long greatestCommonDivisor(long left, long right) {
        long a = Math.abs(left);
        long b = Math.abs(right);
        while (b != 0L) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return Math.max(1L, a);
    }
}
