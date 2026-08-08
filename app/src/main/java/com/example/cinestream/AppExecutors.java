package com.example.cinestream;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-lifetime executors for media work.
 *
 * Keeping these pools shared avoids creating new worker threads every time a RecyclerView
 * adapter is created for search or folder views. MediaStore scans are serialized separately
 * from metadata work so a large library refresh cannot compete with multiple parser jobs.
 */
public final class AppExecutors {

    private static final ExecutorService MEDIA_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cinestream-media-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final ExecutorService METADATA = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "cinestream-metadata");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static ExecutorService mediaIo() {
        return MEDIA_IO;
    }

    public static ExecutorService metadata() {
        return METADATA;
    }

    public static Handler mainHandler() {
        return MAIN;
    }
}
