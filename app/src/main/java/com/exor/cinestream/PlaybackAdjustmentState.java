package com.exor.cinestream;

/** Small policy shared by touch/key controls; never owns Android settings or audio playback. */
final class PlaybackAdjustmentState {
    private int originalVolume = -1;
    private int lastAppliedVolume = -1;

    void recordVolumeChange(int before, int after) {
        if (before == after) return; // A safe-volume/fixed-volume rejection is not an app change.
        if (lastAppliedVolume != before) originalVolume = before;
        lastAppliedVolume = after;
    }

    int volumeToRestore(int actualVolume) {
        // Do not overwrite a later volume choice made outside CineStream.
        int restore = actualVolume == lastAppliedVolume ? originalVolume : -1;
        originalVolume = -1;
        lastAppliedVolume = -1;
        return restore;
    }

    static float resolveBrightness(float windowOverride, int systemBrightness) {
        float brightness = windowOverride >= 0 ? windowOverride : systemBrightness / 255f;
        return Math.max(0f, Math.min(1f, brightness));
    }

    static float clampVolumePercent(float percent) {
        return Math.max(0f, Math.min(200f, percent));
    }
}
