package com.example.cinestream;

/** Resolution labels shared by library rows and media details. */
final class VideoQualityLabels {
    private VideoQualityLabels() {
    }

    static String forDimensions(int width, int height) {
        int longEdge = Math.max(width, height);
        if (longEdge >= 7680) return "8K";
        if (longEdge >= 6144) return "6K";
        if (longEdge >= 5120) return "5K";
        if (longEdge >= 3840) return "4K";
        if (longEdge >= 2560) return "2K";
        if (longEdge >= 1920) return "1080p";
        if (longEdge >= 1280) return "720p";
        if (longEdge >= 854) return "480p";
        if (longEdge >= 640) return "360p";
        if (longEdge >= 426) return "240p";
        return "144p";
    }
}
