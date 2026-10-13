package com.exor.cinestream;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/** App-private, bounded preview cache. A modified/replaced source gets a different identity. */
final class LibraryPreviewCache {
    private LibraryPreviewCache() {}

    static String key(VideoFile video) {
        return identity(video.getContentUri().toString(), video.getSizeBytes(), video.getDateModified());
    }

    static String identity(String uri, long size, long modified) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("preview-v2\n" + uri + "\n" + size + "\n" + modified)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : digest) {
                key.append(Character.forDigit((value >>> 4) & 15, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SharedPreferences metadata(Context context) {
        return context.getApplicationContext().getSharedPreferences("library-preview-v2", Context.MODE_PRIVATE);
    }

    static String metadata(Context context, String key) {
        return metadata(context).getString(key, null);
    }

    static synchronized void putMetadata(Context context, String key, String json) {
        SharedPreferences preferences = metadata(context);
        SharedPreferences.Editor editor = preferences.edit().putString(key, json);
        Map<String, ?> entries = preferences.getAll();
        int excess = entries.size() - (entries.containsKey(key) ? 512 : 511);
        for (String oldKey : entries.keySet()) {
            if (excess <= 0) break;
            if (!key.equals(oldKey)) { editor.remove(oldKey); excess--; }
        }
        editor.apply();
    }

    static File thumbnail(Context context, String key) {
        File file = new File(new File(context.getCacheDir(), "library-previews-v2"), key + ".jpg");
        return file.isFile() && file.length() > 128 ? file : null;
    }

    static synchronized void putThumbnail(Context context, String key, Bitmap bitmap) {
        File directory = new File(context.getCacheDir(), "library-previews-v2");
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        AtomicFile destination = new AtomicFile(new File(directory, key + ".jpg"));
        FileOutputStream stream = null;
        try {
            stream = destination.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                throw new java.io.IOException("Thumbnail compression failed");
            }
            destination.finishWrite(stream);
            stream = null;
            File[] files = directory.listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.jpg"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            long bytes = 0;
            for (int i = 0; i < files.length; i++) {
                bytes += files[i].length();
                // Only evict this cache's old completed previews, never source videos or .part files.
                if (i >= 128 || bytes > 24L * 1024 * 1024) files[i].delete();
            }
        } catch (Exception unavailable) {
            if (stream != null) destination.failWrite(stream);
            Log.w("CineThumbnail", "Preview cache write unavailable", unavailable);
        }
    }
}
