package com.exor.cinestream;

final class MediaFolderLabels {
    static final String INTERNAL_STORAGE_KEY = "__internal_storage_root__";
    static final String INTERNAL_STORAGE_NAME = "Internal storage";

    private MediaFolderLabels() {
    }

    static String keyFromRelativePath(String relativePath) {
        if (relativePath == null) {
            return "Unknown";
        }
        String normalized = trimSeparators(relativePath.trim());
        return normalized.isEmpty() ? INTERNAL_STORAGE_KEY : normalized + "/";
    }

    static String displayNameFromKey(String folderKey) {
        if (INTERNAL_STORAGE_KEY.equals(folderKey)) {
            return INTERNAL_STORAGE_NAME;
        }
        if (folderKey == null || folderKey.trim().isEmpty() || "Unknown".equals(folderKey)) {
            return "Unknown";
        }

        String normalized = trimSeparators(folderKey.trim());
        if (normalized.isEmpty()) {
            return INTERNAL_STORAGE_NAME;
        }
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSeparator(value.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSeparator(char value) {
        return value == '/' || value == '\\';
    }
}
