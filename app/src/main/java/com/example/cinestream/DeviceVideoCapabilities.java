package com.example.cinestream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.media3.common.Format;

import java.util.ArrayList;
import java.util.List;

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
        /** 1 when a performance point covers the format, -1 when points reject it, 0 unknown. */
        public final int performancePointSupport;

        private Assessment(
                Support support,
                String decoderName,
                boolean hardwareAccelerated,
                int performancePointSupport
        ) {
            this.support = support;
            this.decoderName = decoderName;
            this.hardwareAccelerated = hardwareAccelerated;
            this.performancePointSupport = performancePointSupport;
        }
    }

    private DeviceVideoCapabilities() {
    }

    public static Assessment assess(Format format) {
        if (format == null || format.sampleMimeType == null
                || !format.sampleMimeType.startsWith("video/")) {
            return new Assessment(Support.UNKNOWN, null, false, 0);
        }

        int width = format.width;
        int height = format.height;
        float frameRate = format.frameRate;
        if (width <= 0 || height <= 0) {
            return new Assessment(Support.UNKNOWN, null, false, 0);
        }

        MediaCodecInfo[] codecInfos;
        try {
            codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (Exception e) {
            return new Assessment(Support.UNKNOWN, null, false, 0);
        }

        boolean foundDecoder = false;
        String bestDecoder = null;
        boolean bestHardware = false;
        Assessment supportedSoftware = null;

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
                Assessment assessment = new Assessment(
                        Support.SUPPORTED,
                        codecInfo.getName(),
                        hardware,
                        performancePointSupport(videoCapabilities, width, height, frameRate)
                );
                // Prefer a reported hardware-capable decoder when both hardware and platform
                // software decoders advertise support. This gives compatibility targeting the
                // lowest-power viable path.
                if (hardware) {
                    return assessment;
                }
                if (supportedSoftware == null) {
                    supportedSoftware = assessment;
                }
            }
        }

        if (supportedSoftware != null) {
            return supportedSoftware;
        }
        if (!foundDecoder) {
            return new Assessment(Support.NO_PLATFORM_DECODER, null, false, 0);
        }
        return new Assessment(
                Support.EXCEEDS_REPORTED_CAPABILITY,
                bestDecoder,
                bestHardware,
                -1
        );
    }

    /**
     * Finds the highest-quality H.264 size/frame-rate target that the device reports it can decode.
     * Hardware-supported targets are preferred. A platform software H.264 decoder is accepted only
     * when no hardware target is available.
     */
    static CompatibilityVideoPolicy.Target chooseH264CompatibilityTarget(
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate
    ) {
        List<CompatibilityVideoPolicy.Target> targets = chooseH264CompatibilityTargets(
                sourceWidth,
                sourceHeight,
                sourceFrameRate
        );
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * Returns all device-supported H.264 targets in recovery order. Hardware-backed targets are
     * tried before platform-software targets, and quality order is preserved within each group.
     */
    static List<CompatibilityVideoPolicy.Target> chooseH264CompatibilityTargets(
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate
    ) {
        List<CompatibilityVideoPolicy.Target> candidates =
                CompatibilityVideoPolicy.buildCandidates(
                        sourceWidth,
                        sourceHeight,
                        sourceFrameRate
                );
        List<CompatibilityVideoPolicy.Target> hardwareTargets = new ArrayList<>();
        List<CompatibilityVideoPolicy.Target> softwareTargets = new ArrayList<>();
        for (CompatibilityVideoPolicy.Target target : candidates) {
            Format targetFormat = new Format.Builder()
                    .setSampleMimeType("video/avc")
                    .setWidth(target.width)
                    .setHeight(target.height)
                    .setFrameRate(target.frameRate)
                    .build();
            Assessment assessment = assess(targetFormat);
            if (assessment.support != Support.SUPPORTED) {
                continue;
            }
            if (assessment.hardwareAccelerated) {
                hardwareTargets.add(target);
            } else {
                softwareTargets.add(target);
            }
        }
        hardwareTargets.addAll(softwareTargets);
        return hardwareTargets;
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

    private static int performancePointSupport(
            MediaCodecInfo.VideoCapabilities capabilities,
            int width,
            int height,
            float frameRate
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || frameRate <= 0f) {
            return 0;
        }
        try {
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points =
                    capabilities.getSupportedPerformancePoints();
            if (points == null || points.isEmpty()) {
                return 0;
            }
            MediaCodecInfo.VideoCapabilities.PerformancePoint requested =
                    new MediaCodecInfo.VideoCapabilities.PerformancePoint(
                            width,
                            height,
                            Math.max(1, Math.round(frameRate))
                    );
            for (MediaCodecInfo.VideoCapabilities.PerformancePoint point : points) {
                if (point.covers(requested)) {
                    return 1;
                }
            }
            return -1;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
