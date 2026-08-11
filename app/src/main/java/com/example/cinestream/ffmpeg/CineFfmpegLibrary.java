package com.example.cinestream.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/** Loads CineStream's minimal FFmpeg video decoder build and reports supported codecs. */
@UnstableApi
public final class CineFfmpegLibrary {
    private static volatile boolean loadAttempted;
    private static volatile boolean available;

    private CineFfmpegLibrary() {
    }

    public static boolean isAvailable() {
        if (!loadAttempted) {
            synchronized (CineFfmpegLibrary.class) {
                if (!loadAttempted) {
                    try {
                        System.loadLibrary("avutil");
                        System.loadLibrary("avcodec");
                        System.loadLibrary("cinestream_ffmpeg");
                        available = nativeIsUsable();
                    } catch (UnsatisfiedLinkError | SecurityException error) {
                        available = false;
                    }
                    loadAttempted = true;
                }
            }
        }
        return available;
    }

    public static boolean supportsMimeType(@Nullable String mimeType) {
        String codecName = codecNameForMimeType(mimeType);
        return codecName != null && isAvailable() && nativeHasDecoder(codecName);
    }

    @Nullable
    static String codecNameForMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> "h264";
            case MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MV_HEVC -> "hevc";
            case MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX -> "mpeg4";
            case MimeTypes.VIDEO_MPEG2 -> "mpeg2video";
            case MimeTypes.VIDEO_VC1 -> "vc1";
            case MimeTypes.VIDEO_MP42 -> "msmpeg4v2";
            case MimeTypes.VIDEO_MP43 -> "msmpeg4v3";
            case MimeTypes.VIDEO_H263 -> "h263";
            case MimeTypes.VIDEO_FLV -> "flv";
            case MimeTypes.VIDEO_MJPEG -> "mjpeg";
            default -> null;
        };
    }

    @Nullable
    public static String getVersion() {
        return isAvailable() ? nativeGetVersion() : null;
    }

    private static native boolean nativeIsUsable();

    private static native boolean nativeHasDecoder(String codecName);

    private static native String nativeGetVersion();
}
