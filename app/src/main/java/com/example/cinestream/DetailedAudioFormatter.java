package com.example.cinestream;

import androidx.media3.common.C;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure formatter/merger for per-track Detailed Info audio metadata. */
final class DetailedAudioFormatter {

    static final class Track {
        final int ordinal;
        final String title;
        String codec;
        int channelCount;
        int sampleRate;
        final int selectionFlags;

        Track(int ordinal, String title, String codec, int channelCount, int sampleRate,
              int selectionFlags) {
            this.ordinal = ordinal;
            this.title = title;
            this.codec = normalizeCodec(codec);
            this.channelCount = Math.max(0, channelCount);
            this.sampleRate = Math.max(0, sampleRate);
            this.selectionFlags = selectionFlags;
        }
    }

    private DetailedAudioFormatter() {}

    static void enrichMissing(
            List<Track> tracks,
            List<String> fallbackCodecs,
            List<Integer> fallbackChannels,
            List<Integer> fallbackSampleRates
    ) {
        if (tracks == null || fallbackCodecs == null || fallbackChannels == null
                || fallbackSampleRates == null) return;
        int size = tracks.size();
        if (size == 0 || fallbackCodecs.size() != size || fallbackChannels.size() != size
                || fallbackSampleRates.size() != size) return;

        for (int i = 0; i < size; i++) {
            Track track = tracks.get(i);
            String fallbackCodec = normalizeCodec(fallbackCodecs.get(i));
            if (isWeakCodec(track.codec) && !isWeakCodec(fallbackCodec)) {
                track.codec = fallbackCodec;
            }
            int fallbackCh = safeInt(fallbackChannels.get(i));
            int fallbackHz = safeInt(fallbackSampleRates.get(i));
            if (track.channelCount <= 0 && fallbackCh > 0) track.channelCount = fallbackCh;
            if (track.sampleRate <= 0 && fallbackHz > 0) track.sampleRate = fallbackHz;
        }
    }

    static List<String> detailLines(List<Track> tracks) {
        List<String> lines = new ArrayList<>();
        if (tracks == null) return lines;
        for (Track track : tracks) lines.add(detailLine(track));
        return lines;
    }

    static String multiLineCodecs(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return "Unknown";
        if (tracks.size() == 1) {
            Track t = tracks.get(0);
            return t.title + " — " + t.codec;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            sb.append("Track ").append(i + 1).append(": ")
                    .append(t.title).append(" — ").append(t.codec);
            if (i < tracks.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    static String multiLineDetails(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return "Unknown";
        if (tracks.size() == 1) return detailLine(tracks.get(0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            sb.append("Track ").append(i + 1).append(": ").append(detailLine(tracks.get(i)));
            if (i < tracks.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static String detailLine(Track track) {
        List<String> parts = new ArrayList<>();
        parts.add(track.codec);
        if (track.channelCount > 0) parts.add(AudioTrackFormatter.channelLabel(track.channelCount));
        if (track.sampleRate > 0) parts.add(formatSampleRate(track.sampleRate));
        if ((track.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) parts.add("Default");
        return track.title + " — " + String.join(" • ", parts);
    }

    private static String formatSampleRate(int sampleRate) {
        if (sampleRate >= 1000 && sampleRate % 1000 == 0) return (sampleRate / 1000) + " kHz";
        if (sampleRate >= 1000) return String.format(Locale.US, "%.1f kHz", sampleRate / 1000f);
        return sampleRate + " Hz";
    }

    private static String normalizeCodec(String codec) {
        if (codec == null || codec.trim().isEmpty()) return "Audio";
        return codec.trim();
    }

    private static boolean isWeakCodec(String codec) {
        if (codec == null) return true;
        String c = codec.trim().toLowerCase(Locale.US);
        return c.isEmpty() || "audio".equals(c) || "unknown".equals(c)
                || c.contains("unsupported") || c.contains("not reported");
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
