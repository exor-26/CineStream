package com.example.cinestream;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** File identities for completed and in-progress progressive compatibility segments. */
final class ProgressiveCompatibilityCache {
    private static final String PART_SUFFIX = ".mp4.part";

    private ProgressiveCompatibilityCache() {
    }

    static String stableSourceIdentity(String sourceMaterial) {
        String material = sourceMaterial != null ? sourceMaterial : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                builder.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(material.hashCode());
        }
    }

    static File completedSegment(
            File cacheDir,
            String sourceKey,
            ProgressiveCompatibilityPolicy.SegmentWindow window,
            CompatibilityVideoPolicy.Target target
    ) {
        return new File(
                cacheDir,
                "progressive_" + sourceKey
                        + "_s" + String.format(Locale.US, "%06d", window.index)
                        + "_" + window.startMs + "-" + window.endMs
                        + "_" + target.width + "x" + target.height
                        + "_" + Math.round(target.frameRate)
                        + ".mp4"
        );
    }

    static File incompleteSegment(File completedSegment) {
        String name = completedSegment.getName();
        if (name.endsWith(".mp4")) {
            name = name.substring(0, name.length() - 4);
        }
        return new File(completedSegment.getParentFile(), name + PART_SUFFIX);
    }

    static boolean isIncompleteSegment(File file) {
        return file != null && file.getName().endsWith(PART_SUFFIX);
    }

    static boolean isCompletedSegment(File file) {
        return file != null
                && file.getName().startsWith("progressive_")
                && file.getName().endsWith(".mp4")
                && !isIncompleteSegment(file);
    }
}
