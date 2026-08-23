package com.example.cinestream;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import androidx.media3.common.C;
import androidx.media3.common.Format;

/** Captures runtime resource facts without using device identity. */
final class RuntimeVideoResourceMonitor {
    private final Context context;
    private final ActivityManager activityManager;
    private final PowerManager powerManager;
    private final WindowManager windowManager;

    private long previousCpuTimeMs = -1L;
    private long previousWallTimeMs = -1L;
    private double lastCpuLoad = Double.NaN;

    RuntimeVideoResourceMonitor(Context context) {
        this.context = context.getApplicationContext();
        activityManager = (ActivityManager) this.context.getSystemService(Context.ACTIVITY_SERVICE);
        powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        sampleCpuLoad();
    }

    VideoResourceGovernor.Snapshot capture(
            Format format,
            DeviceVideoCapabilities.Assessment assessment,
            boolean hardwarePlaybackFailed
    ) {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        int thermalStatus = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalStatus = powerManager.getCurrentThermalStatus();
        }

        int displayWidth = context.getResources().getDisplayMetrics().widthPixels;
        int displayHeight = context.getResources().getDisplayMetrics().heightPixels;
        float refreshRate = 0f;
        if (windowManager != null) {
            Display display = windowManager.getDefaultDisplay();
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                displayWidth = size.x;
                displayHeight = size.y;
                refreshRate = display.getRefreshRate();
            }
        }

        boolean hdrTransfer = format != null
                && format.colorInfo != null
                && (format.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                || format.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
        int reportedPerformance = 0;
        if (assessment != null) {
            if (assessment.performancePointSupport != 0) {
                reportedPerformance = assessment.performancePointSupport;
            } else if (assessment.support == DeviceVideoCapabilities.Support.SUPPORTED) {
                reportedPerformance = 1;
            } else if (assessment.support
                    == DeviceVideoCapabilities.Support.EXCEEDS_REPORTED_CAPABILITY) {
                reportedPerformance = -1;
            }
        }
        if (hardwarePlaybackFailed && reportedPerformance > 0) {
            reportedPerformance = 0;
        }

        return new VideoResourceGovernor.Snapshot(
                memoryInfo.availMem,
                memoryInfo.totalMem,
                memoryInfo.threshold,
                memoryInfo.lowMemory,
                Runtime.getRuntime().availableProcessors(),
                sampleCpuLoad(),
                thermalStatus,
                format != null ? format.width : Format.NO_VALUE,
                format != null ? format.height : Format.NO_VALUE,
                format != null ? format.frameRate : Format.NO_VALUE,
                format != null && format.averageBitrate != Format.NO_VALUE
                        ? format.averageBitrate : 0L,
                VideoResourceGovernor.estimateBitDepth(
                        format != null ? format.codecs : null,
                        hdrTransfer
                ),
                displayWidth,
                displayHeight,
                refreshRate,
                reportedPerformance,
                hardwarePlaybackFailed
        );
    }

    private double sampleCpuLoad() {
        long wallTimeMs = SystemClock.elapsedRealtime();
        long cpuTimeMs = Process.getElapsedCpuTime();
        if (previousWallTimeMs >= 0L) {
            long elapsedMs = wallTimeMs - previousWallTimeMs;
            long usedCpuMs = cpuTimeMs - previousCpuTimeMs;
            if (elapsedMs >= VideoResourceGovernor.MIN_CPU_SAMPLE_MS && usedCpuMs >= 0L) {
                int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
                lastCpuLoad = Math.max(
                        0d,
                        Math.min(1d, (double) usedCpuMs / (elapsedMs * processors))
                );
                previousWallTimeMs = wallTimeMs;
                previousCpuTimeMs = cpuTimeMs;
                return lastCpuLoad;
            }
        } else {
            previousWallTimeMs = wallTimeMs;
            previousCpuTimeMs = cpuTimeMs;
        }
        return lastCpuLoad;
    }
}
