package com.exor.cinestream;

/** Pure bounds checks for untrusted MKV SeekHead offsets and header sizes. */
final class MatroskaHeaderReference {
    static final long MAX_HEADER_BYTES = 8L * 1024 * 1024;
    private MatroskaHeaderReference() {}

    static long resolve(long segmentStart, long relativeOffset, long inputLength) {
        if (segmentStart < 0 || relativeOffset < 0
                || relativeOffset > Long.MAX_VALUE - segmentStart) return -1;
        long position = segmentStart + relativeOffset;
        return inputLength >= 0 && position >= inputLength ? -1 : position;
    }

    static boolean boundedSize(long size) {
        return size >= 0 && size <= MAX_HEADER_BYTES;
    }
}
