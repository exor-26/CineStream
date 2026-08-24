package com.example.cinestream;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared app-lifetime executors for media, metadata and list/search work. */
public final class AppExecutors {

    private static final ExecutorService MEDIA_IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cinestream-media-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    // Playback recovery must not wait behind library scans, thumbnail extraction or an earlier
    // segment validation. A dedicated serial executor keeps cache discovery deterministic without
    // adding competing video decoder work.
    private static final ExecutorService PLAYBACK_RECOVERY = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cinestream-playback-recovery");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final ExecutorService METADATA = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "cinestream-metadata");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final ExecutorService LIST_WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cinestream-list-work");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static ExecutorService mediaIo() {
        return MEDIA_IO;
    }

    public static ExecutorService playbackRecovery() {
        return PLAYBACK_RECOVERY;
    }

    public static ExecutorService metadata() {
        return METADATA;
    }

    public static ExecutorService listWork() {
        return LIST_WORK;
    }

    public static Handler mainHandler() {
        return MAIN;
    }
}
