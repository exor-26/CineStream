package com.example.cinestream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.media3.common.Format;

/**
 * Conservative capability probe for the currently selected video format.
 *
 * This does not block playback. OEM codec capability tables can under-report what a device can
 * really do, so the result is used for diagnostics/recovery decisions rather than as an absolute
 * gate. A positive SUPPORTED result means at least one platform decoder reports support for the
 * requested size/rate.
 */
public final class DeviceVideoCapabilities {

    public enum Support {
        SUPPORTED,
        EXCEEDS_REPORTED_CAPABILITY,
        NO_PLATFORM_DECODER,
        UNKNOWN
    }

    public static final class Assessment {
        public final Support support;
        public final String decoderName;
        public final boolean hardwareAccelerated;

        private Assessment(Support support, String decoderName, boolean hardwareAccelerated) {
            this.support = support;
            this.decoderName = decoderName;
            this.hardwareAccelerated = hardwareAccelerated;
        }
    }

    private DeviceVideoCapabilities() {
    }

    public static Assessment assess(Format format) {
        if (format == null || format.sampleMimeType == null
                || !format.sampleMimeType.startsWith("video/")) {
            return new Assessment(Support.UNKNOWN, null, false);
        }

        int width = format.width;
        int height = format.height;
        float frameRate = format.frameRate;
        if (width <= 0 || height <= 0) {
            return new Assessment(Support.UNKNOWN, null, false);
        }

        MediaCodecInfo[] codecInfos;
        try {
            codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (Exception e) {
            return new Assessment(Support.UNKNOWN, null, false);
        }

        boolean foundDecoder = false;
        String bestDecoder = null;
        boolean bestHardware = false;

        for (MediaCodecInfo codecInfo : codecInfos) {
            if (codecInfo.isEncoder()) {
                continue;
            }

            MediaCodecInfo.CodecCapabilities capabilities;
            try {
                capabilities = codecInfo.getCapabilitiesForType(format.sampleMimeType);
            } catch (Exception ignored) {
                continue;
            }

            foundDecoder = true;
            boolean hardware = isHardwareAccelerated(codecInfo);
            if (bestDecoder == null || (hardware && !bestHardware)) {
                bestDecoder = codecInfo.getName();
                bestHardware = hardware;
            }

            MediaCodecInfo.VideoCapabilities videoCapabilities;
            try {
                videoCapabilities = capabilities.getVideoCapabilities();
            } catch (Exception ignored) {
                continue;
            }
            if (videoCapabilities == null) {
                continue;
            }

            boolean supported;
            try {
                supported = frameRate > 0f
                        ? videoCapabilities.areSizeAndRateSupported(width, height, frameRate)
                        : videoCapabilities.isSizeSupported(width, height);
            } catch (Exception ignored) {
                supported = false;
            }

            if (supported) {
                return new Assessment(Support.SUPPORTED, codecInfo.getName(), hardware);
            }
        }

        if (!foundDecoder) {
            return new Assessment(Support.NO_PLATFORM_DECODER, null, false);
        }
        return new Assessment(
                Support.EXCEEDS_REPORTED_CAPABILITY,
                bestDecoder,
                bestHardware
        );
    }

    private static boolean isHardwareAccelerated(MediaCodecInfo codecInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return codecInfo.isHardwareAccelerated();
            } catch (Exception ignored) {
            }
        }

        // Pre-Android 10 has no authoritative API. This heuristic only affects diagnostics.
        String name = codecInfo.getName().toLowerCase(java.util.Locale.US);
        return !(name.startsWith("omx.google.")
                || name.startsWith("c2.android.")
                || name.contains("ffmpeg")
                || name.contains("sw."));
    }
}
