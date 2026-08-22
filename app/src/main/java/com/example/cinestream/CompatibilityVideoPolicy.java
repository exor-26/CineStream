package com.example.cinestream;

import java.util.ArrayList;
import java.util.List;

/** Pure compatibility-target policy shared by runtime logic and JVM tests. */
final class CompatibilityVideoPolicy {

    static final class Target {
        final int width;
        final int height;
        final float frameRate;

        Target(int width, int height, float frameRate) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }

        @Override
        public String toString() {
            return width + "x" + height + (frameRate > 0f ? "@" + Math.round(frameRate) : "");
        }
    }

    private CompatibilityVideoPolicy() {}

    static List<Target> buildCandidates(int sourceWidth, int sourceHeight, float sourceFrameRate) {
        List<Target> candidates = new ArrayList<>();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return candidates;
        }

        float originalRate = sourceFrameRate > 0f ? sourceFrameRate : 30f;
        boolean highFrameRate = originalRate > 31f;
        int[][] landscapeBounds = new int[][]{
                {1920, 1080},
                {1280, 720},
                {854, 480}
        };

        for (int[] bounds : landscapeBounds) {
            int maxWidth = sourceWidth >= sourceHeight ? bounds[0] : bounds[1];
            int maxHeight = sourceWidth >= sourceHeight ? bounds[1] : bounds[0];
            int[] size = fitWithin(sourceWidth, sourceHeight, maxWidth, maxHeight);
            addUnique(candidates, new Target(size[0], size[1], originalRate));
            if (highFrameRate) {
                addUnique(candidates, new Target(size[0], size[1], 30f));
            }
        }
        return candidates;
    }

    static int[] fitWithin(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return new int[]{0, 0};
        }
        double scale = Math.min(
                1d,
                Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight)
        );
        int width = evenAtLeastTwo((int) Math.round(sourceWidth * scale));
        int height = evenAtLeastTwo((int) Math.round(sourceHeight * scale));
        return new int[]{width, height};
    }

    static boolean isLowerFrameRateVariant(Target current, Target next) {
        return current != null
                && next != null
                && current.width == next.width
                && current.height == next.height
                && current.frameRate > 31f
                && next.frameRate > 0f
                && next.frameRate <= 31f;
    }

    private static int evenAtLeastTwo(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static void addUnique(List<Target> targets, Target candidate) {
        for (Target target : targets) {
            if (target.width == candidate.width
                    && target.height == candidate.height
                    && Math.abs(target.frameRate - candidate.frameRate) < 0.5f) {
                return;
            }
        }
        targets.add(candidate);
    }
}
