package com.exor.cinestream;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.LruCache;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Small, silent, bounded thumbnail fallback using the same source/parser as playback. */
@UnstableApi
final class CineThumbnailProbe {
    private static final int WIDTH = 336;
    private static final int HEIGHT = 216;
    private static final Semaphore DECODER_SLOT = new Semaphore(1);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(12);
    private static volatile CountDownLatch activeRequest;
    private static volatile boolean suspended;
    private CineThumbnailProbe() {}

    static void cancelActive() {
        suspended = true;
        CountDownLatch request = activeRequest;
        if (request != null) request.countDown();
    }

    static void resume() { suspended = false; }

    static Bitmap probe(Context context, Uri uri, String sourceIdentity) {
        Bitmap cached = CACHE.get(sourceIdentity);
        if (cached != null && !cached.isRecycled()) return cached;
        if (suspended) return null;
        boolean acquired;
        try { acquired = DECODER_SLOT.tryAcquire(6, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        if (!acquired) return null;
        if (suspended) { DECODER_SLOT.release(); return null; }
        cached = CACHE.get(sourceIdentity);
        if (cached != null && !cached.isRecycled()) {
            DECODER_SLOT.release();
            return cached;
        }

        HandlerThread thread = new HandlerThread("CineThumbnail");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1);
        activeRequest = ready;
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Bitmap> result = new AtomicReference<>();
        ExoPlayer[] player = new ExoPlayer[1];
        ThumbnailTextureSurface[] output = new ThumbnailTextureSurface[1];
        Context app = context.getApplicationContext();
        handler.post(() -> {
            if (finished.get()) return;
            try {
                output[0] = new ThumbnailTextureSurface(WIDTH, HEIGHT, handler, () -> {
                    if (finished.get()) return;
                    try {
                        Bitmap frame = output[0].capture();
                        if (finished.compareAndSet(false, true)) result.set(frame);
                        else frame.recycle();
                        ready.countDown();
                    } catch (Exception unavailable) {
                        Log.w("CineThumbnail", "Texture capture unavailable", unavailable);
                        ready.countDown();
                    }
                });
                DefaultTrackSelector selector = new DefaultTrackSelector(app);
                selector.setParameters(selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
                player[0] = new ExoPlayer.Builder(app).setLooper(thread.getLooper())
                        .setTrackSelector(selector).setMediaSourceFactory(CineMediaSources.factory(app)).build();
                player[0].addListener(new Player.Listener() {
                    @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                        if (output[0] != null && size.width > 0 && size.height > 0) {
                            output[0].setVideoAspect(size.width * size.pixelWidthHeightRatio / size.height);
                        }
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        Log.w("CineThumbnail", "Preview playback unavailable", error);
                        ready.countDown();
                    }
                });
                player[0].setVideoSurface(output[0].surface());
                player[0].setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                player[0].setMediaItem(MediaItem.fromUri(uri));
                player[0].seekTo(1000);
                player[0].prepare();
                player[0].setPlayWhenReady(true);
            } catch (Exception unavailable) {
                Log.w("CineThumbnail", "Preview setup unavailable", unavailable);
                ready.countDown();
            }
        });
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                Log.d("CineThumbnail", "Preview timed out");
            }
            Bitmap frame = result.get();
            if (frame != null) {
                CACHE.put(sourceIdentity, frame);
                LibraryPreviewCache.putThumbnail(app, sourceIdentity, frame);
            }
            return frame;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            finished.set(true);
            if (activeRequest == ready) activeRequest = null;
            handler.post(() -> {
                try {
                    if (player[0] != null) player[0].release();
                } finally {
                    try {
                        if (output[0] != null) output[0].close();
                    } finally {
                        DECODER_SLOT.release();
                        thread.quitSafely();
                    }
                }
            });
        }
    }
}
