package com.example.cinestream;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds stable subtitle names from semantic track metadata instead of muxer advertising labels. */
final class SubtitleTrackFormatter {
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?i).*(?:^|[^a-z0-9])(?:[a-z0-9-]+\\.)+(?:com|net|org|in|co|io|me|tv|to|xyz)(?:[^a-z0-9]|$).*"
    );

    private SubtitleTrackFormatter() {}

    static String buildTitle(int ordinal, Format format) {
        return buildTitle(ordinal, format.language, format.label, format.roleFlags, format.selectionFlags);
    }

    static String buildTitle(
            int ordinal,
            String languageCode,
            String rawLabel,
            int roleFlags,
            int selectionFlags
    ) {
        String language = displayLanguage(languageCode);
        String role = roleLabel(roleFlags, selectionFlags);
        String cleanLabel = cleanHumanLabel(rawLabel);

        if (language != null) {
            return role == null ? language : language + " • " + role;
        }
        if (cleanLabel != null) {
            return role == null || containsIgnoreCase(cleanLabel, role)
                    ? cleanLabel : cleanLabel + " • " + role;
        }
        return role == null
                ? "Caption " + Math.max(1, ordinal)
                : "Caption " + Math.max(1, ordinal) + " • " + role;
    }

    static String buildTechnicalDetails(Format format, boolean supported) {
        return buildTechnicalDetails(format.sampleMimeType, format.selectionFlags, supported);
    }

    static String buildTechnicalDetails(String mime, int selectionFlags, boolean supported) {
        List<String> parts = new ArrayList<>();
        parts.add(codecLabel(mime));
        if ((selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) parts.add("Default");
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) parts.add("Forced");
        if (!supported) parts.add("Limited");
        return String.join(" • ", parts);
    }

    static String codecLabel(String mime) {
        if (mime == null || mime.trim().isEmpty()) return "Subtitle";
        switch (mime) {
            case "application/x-subrip": return "SRT";
            case "text/x-ssa": return "ASS/SSA";
            case "text/vtt":
            case "application/x-mp4-vtt": return "WebVTT";
            case "application/ttml+xml": return "TTML";
            case "application/x-quicktime-tx3g": return "TX3G";
            case "application/pgs": return "PGS";
            case "application/dvbsubs": return "DVB";
            case "application/cea-608":
            case "application/x-mp4-cea-608": return "CEA-608";
            case "application/cea-708": return "CEA-708";
            case "application/vobsub": return "VobSub";
            default:
                String value = mime.replace("application/", "").replace("text/", "");
                return value.isEmpty() ? "Subtitle" : value.toUpperCase(Locale.US);
        }
    }

    private static String roleLabel(int roleFlags, int selectionFlags) {
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) return "Forced";
        boolean music = (roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0;
        boolean dialog = (roleFlags & C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0;
        if (music && dialog) return "SDH";
        if ((roleFlags & C.ROLE_FLAG_CAPTION) != 0) return "Captions";
        if ((roleFlags & C.ROLE_FLAG_SIGN) != 0) return "Sign";
        if ((roleFlags & C.ROLE_FLAG_EASY_TO_READ) != 0) return "Easy read";
        if ((roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) return "Commentary";
        if (music) return "Sound descriptions";
        if (dialog) return "Dialogue";
        if ((roleFlags & C.ROLE_FLAG_MAIN) != 0) return "Main";
        return null;
    }

    private static String displayLanguage(String language) {
        if (language == null) return null;
        String value = language.trim();
        if (value.isEmpty() || "und".equalsIgnoreCase(value)) return null;
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        String display = locale.getDisplayLanguage(Locale.ENGLISH);
        if (display != null && !display.trim().isEmpty() && !display.equalsIgnoreCase(value)) {
            return capitalize(display.trim());
        }
        return value.toUpperCase(Locale.US);
    }

    private static String cleanHumanLabel(String label) {
        if (label == null) return null;
        String value = label.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("www.") || lower.contains("t.me/")
                || DOMAIN_PATTERN.matcher(value).matches()) return null;
        if (lower.contains("telegram") || lower.contains("download from")
                || lower.contains("visit our") || lower.contains("join our")
                || lower.contains("website:")) return null;
        return value.length() > 80 ? null : value;
    }

    private static boolean containsIgnoreCase(String value, String part) {
        return value.toLowerCase(Locale.US).contains(part.toLowerCase(Locale.US));
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.ENGLISH) + value.substring(1);
    }
}
