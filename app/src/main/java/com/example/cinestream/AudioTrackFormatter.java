package com.example.cinestream;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds stable, user-facing audio track names from technical media metadata. */
final class AudioTrackFormatter {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?i).*(?:^|[^a-z0-9])(?:[a-z0-9-]+\\.)+(?:com|net|org|in|co|io|me|tv|to|xyz)(?:[^a-z0-9]|$).*"
    );

    private AudioTrackFormatter() {
    }

    static String buildTitle(int ordinal, Format format) {
        String language = displayLanguage(format.language);
        String role = roleLabel(format);
        String label = cleanHumanLabel(format.label);

        if (language != null) {
            if (role != null) {
                return language + " • " + role;
            }
            return language;
        }

        if (label != null) {
            if (role != null && !containsIgnoreCase(label, role)) {
                return label + " • " + role;
            }
            return label;
        }

        if (role != null) {
            return "Audio " + Math.max(1, ordinal) + " • " + role;
        }
        return "Audio " + Math.max(1, ordinal);
    }

    static String buildTechnicalDetails(Format format, boolean supported) {
        List<String> parts = new ArrayList<>();
        parts.add(codecLabel(format));

        if (format.channelCount > 0) {
            parts.add(channelLabel(format.channelCount));
        }
        if (format.sampleRate > 0) {
            parts.add(formatSampleRate(format.sampleRate));
        }

        if ((format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
            parts.add("Default");
        }
        if (!supported) {
            parts.add("Limited");
        }
        return String.join(" • ", parts);
    }

    static String buildInfoLine(int ordinal, Format format) {
        return buildTitle(ordinal, format) + " — " + buildTechnicalDetails(format, true);
    }

    static String codecLabel(Format format) {
        String mime = format.sampleMimeType;
        if (mime != null) {
            switch (mime) {
                case "audio/mp4a-latm": return "AAC";
                case "audio/ac3": return "AC-3";
                case "audio/eac3": return "E-AC-3";
                case "audio/eac3-joc": return "E-AC-3 JOC (Atmos)";
                case "audio/ac4": return "AC-4";
                case "audio/vnd.dts": return "DTS";
                case "audio/vnd.dts.hd": return "DTS-HD";
                case "audio/true-hd": return "TrueHD";
                case "audio/opus": return "Opus";
                case "audio/vorbis": return "Vorbis";
                case "audio/mpeg": return "MP3";
                case "audio/flac": return "FLAC";
                case "audio/raw": return "PCM";
                default:
                    String value = mime.startsWith("audio/") ? mime.substring(6) : mime;
                    if (!value.trim().isEmpty()) {
                        return value.toUpperCase(Locale.US);
                    }
                    break;
            }
        }
        if (format.codecs != null && !format.codecs.trim().isEmpty()) {
            return format.codecs.trim().toUpperCase(Locale.US);
        }
        return "Audio";
    }

    static String channelLabel(int channelCount) {
        switch (channelCount) {
            case 1: return "Mono (1 ch)";
            case 2: return "Stereo (2 ch)";
            case 6: return "5.1 (6 ch)";
            case 8: return "7.1 (8 ch)";
            default: return channelCount + " ch";
        }
    }

    private static String formatSampleRate(int sampleRate) {
        if (sampleRate >= 1000 && sampleRate % 1000 == 0) {
            return (sampleRate / 1000) + " kHz";
        }
        if (sampleRate >= 1000) {
            return String.format(Locale.US, "%.1f kHz", sampleRate / 1000f);
        }
        return sampleRate + " Hz";
    }

    private static String displayLanguage(String language) {
        if (language == null) {
            return null;
        }
        String value = language.trim();
        if (value.isEmpty() || "und".equalsIgnoreCase(value)) {
            return null;
        }

        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        String display = locale.getDisplayLanguage(Locale.ENGLISH);
        if (display != null && !display.trim().isEmpty()
                && !display.equalsIgnoreCase(value)) {
            return capitalize(display.trim());
        }
        return value.toUpperCase(Locale.US);
    }

    private static String roleLabel(Format format) {
        int flags = format.roleFlags;
        if ((flags & C.ROLE_FLAG_DUB) != 0) return "Dub";
        if ((flags & C.ROLE_FLAG_COMMENTARY) != 0) return "Commentary";
        if ((flags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return "Audio description";
        if ((flags & C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY) != 0) return "Enhanced dialogue";

        String label = format.label;
        if (label != null && label.toLowerCase(Locale.US).contains("original")) {
            return "Original";
        }
        if ((flags & C.ROLE_FLAG_MAIN) != 0) return "Main";
        return null;
    }

    private static String cleanHumanLabel(String label) {
        if (label == null) {
            return null;
        }
        String value = label.trim().replaceAll("\\s+", " ");
        if (value.isEmpty() || value.length() > 80) {
            return null;
        }

        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("www.")
                || lower.contains("t.me/")
                || lower.contains("telegram")
                || lower.contains("download from")
                || DOMAIN_PATTERN.matcher(lower).matches()) {
            return null;
        }
        return value;
    }

    private static boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(Locale.US).contains(value.toLowerCase(Locale.US));
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        if (value.length() == 1) return value.toUpperCase(Locale.US);
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }
}
