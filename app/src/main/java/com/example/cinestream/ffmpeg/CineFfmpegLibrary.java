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
                        // libavcodec/libavutil are linked into this one JNI shared library so
                        // Android has no extra FFmpeg SONAMEs to load at runtime.
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

    /** Returns whether CineStream's minimal native build is intended to provide this video MIME. */
    public static boolean isDeclaredVideoMimeType(@Nullable String mimeType) {
        return codecNameForMimeType(mimeType) != null;
    }

    /** Returns whether the installed native library actually contains a decoder for this MIME. */
    public static boolean supportsMimeType(@Nullable String mimeType) {
        String codecName = codecNameForMimeType(mimeType);
        return codecName != null && isAvailable() && nativeHasDecoder(codecName);
    }

    /** Includes codecs used only as offline Transformer inputs. */
    public static boolean supportsTransformerMimeType(@Nullable String mimeType) {
        String codecName = codecNameForTransformerMimeType(mimeType);
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
            case MimeTypes.VIDEO_VP8 -> "vp8";
            default -> null;
        };
    }

    @Nullable
    static String codecNameForTransformerMimeType(@Nullable String mimeType) {
        // Media3 exposes dvhe/dvh1 samples as video/dolby-vision rather than video/hevc. Devices
        // without a Dolby Vision MediaCodec therefore leave the track unselected. FFmpeg can still
        // decode the HEVC base layer for offline compatibility conversion.
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
            return "hevc";
        }
        if (MimeTypes.VIDEO_AV1.equals(mimeType)) {
            return "av1";
        }
        if (MimeTypes.VIDEO_VP9.equals(mimeType)) {
            return "vp9";
        }
        return codecNameForMimeType(mimeType);
    }

    @Nullable
    public static String getVersion() {
        return isAvailable() ? nativeGetVersion() : null;
    }

    private static native boolean nativeIsUsable();

    private static native boolean nativeHasDecoder(String codecName);

    private static native String nativeGetVersion();
}
