package com.example.cinestream;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight process-wide cache for metadata that Android has already indexed in MediaStore.
 *
 * Library rows only need duration and display quality. Reading those values from MediaStore is
 * substantially cheaper than opening every file with MediaMetadataRetriever + MediaExtractor.
 * Full parsing is still handled by VideoAdapter when indexed values are missing or when the user
 * explicitly opens Detailed Info.
 */
public final class MediaStoreMetadataIndex {

    public interface Callback {
        void onResult(IndexedMetadata metadata);
    }

    public static final class IndexedMetadata {
        public final long durationMs;
        public final int width;
        public final int height;

        private IndexedMetadata(long durationMs, int width, int height) {
            this.durationMs = Math.max(0L, durationMs);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        public boolean hasDuration() {
            return durationMs > 0L;
        }

        public boolean hasResolution() {
            return width > 0 && height > 0;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<Long, IndexedMetadata> CACHE = new HashMap<>();
    private static final List<PendingRequest> WAITERS = new ArrayList<>();

    private static boolean loading;
    private static boolean loaded;

    private MediaStoreMetadataIndex() {
    }

    public static IndexedMetadata peek(long mediaId) {
        synchronized (LOCK) {
            return CACHE.get(mediaId);
        }
    }

    public static void request(Context context, long mediaId, Callback callback) {
        Context appContext = context.getApplicationContext();
        boolean startBulkLoad = false;
        boolean querySingle = false;
        IndexedMetadata immediate = null;

        synchronized (LOCK) {
            if (loaded) {
                immediate = CACHE.get(mediaId);
                if (immediate == null) {
                    querySingle = true;
                }
            } else {
                WAITERS.add(new PendingRequest(mediaId, callback));
                if (!loading) {
                    loading = true;
                    startBulkLoad = true;
                }
            }
        }

        if (immediate != null) {
            IndexedMetadata result = immediate;
            AppExecutors.mainHandler().post(() -> callback.onResult(result));
            return;
        }

        if (querySingle) {
            AppExecutors.mediaIo().execute(() -> {
                IndexedMetadata metadata = queryOne(appContext, mediaId);
                if (metadata != null) {
                    synchronized (LOCK) {
                        CACHE.put(mediaId, metadata);
                    }
                }
                AppExecutors.mainHandler().post(() -> callback.onResult(metadata));
            });
            return;
        }

        if (startBulkLoad) {
            AppExecutors.mediaIo().execute(() -> loadAll(appContext));
        }
    }

    private static void loadAll(Context context) {
        Map<Long, IndexedMetadata> loadedValues = new HashMap<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                buildProjection(),
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                do {
                    if (idIndex < 0) {
                        break;
                    }
                    long id = cursor.getLong(idIndex);
                    loadedValues.put(id, readMetadata(cursor));
                } while (cursor.moveToNext());
            }
        } catch (Exception ignored) {
            // Missing/OEM-specific columns must never break the video library. The adapter will
            // simply use its existing per-file fallback for entries without indexed metadata.
        }

        List<PendingRequest> callbacks;
        synchronized (LOCK) {
            CACHE.clear();
            CACHE.putAll(loadedValues);
            loaded = true;
            loading = false;
            callbacks = new ArrayList<>(WAITERS);
            WAITERS.clear();
        }

        AppExecutors.mainHandler().post(() -> {
            for (PendingRequest pending : callbacks) {
                IndexedMetadata metadata;
                synchronized (LOCK) {
                    metadata = CACHE.get(pending.mediaId);
                }
                pending.callback.onResult(metadata);
            }
        });
    }

    private static IndexedMetadata queryOne(Context context, long mediaId) {
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                buildProjectionWithoutId(),
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return readMetadata(cursor);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String[] buildProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
            };
        }
        return new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
        };
    }

    private static String[] buildProjectionWithoutId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
            };
        }
        return new String[]{
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
        };
    }

    private static IndexedMetadata readMetadata(Cursor cursor) {
        long durationMs = 0L;
        int width = 0;
        int height = 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
            if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                durationMs = cursor.getLong(durationIndex);
            }
        }

        int widthIndex = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
        if (widthIndex >= 0 && !cursor.isNull(widthIndex)) {
            width = cursor.getInt(widthIndex);
        }

        int heightIndex = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);
        if (heightIndex >= 0 && !cursor.isNull(heightIndex)) {
            height = cursor.getInt(heightIndex);
        }

        return new IndexedMetadata(durationMs, width, height);
    }

    private static final class PendingRequest {
        final long mediaId;
        final Callback callback;

        PendingRequest(long mediaId, Callback callback) {
            this.mediaId = mediaId;
            this.callback = callback;
        }
    }
}
