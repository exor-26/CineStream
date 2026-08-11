package com.example.cinestream;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Creates and caches a video-only H.264 compatibility rendition.
 *
 * The original file remains the source of audio and subtitle tracks during playback. This keeps
 * compatibility conversion focused on the expensive/incompatible video stream and avoids losing
 * multi-audio or embedded caption metadata.
 */
@UnstableApi
final class CompatibilityVideoTranscoder {
    private static final String TAG = "CompatVideoTranscoder";
    private static final long MAX_CACHE_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    interface Callback {
        void onReady(File file, CompatibilityVideoPolicy.Target target, boolean fromCache);
        void onError(String message, Throwable error);
    }

    static final class Session {
        private final Transformer transformer;
        private final File outputFile;
        private boolean finished;

        Session(Transformer transformer, File outputFile) {
            this.transformer = transformer;
            this.outputFile = outputFile;
        }

        void markFinished() {
            finished = true;
        }

        void cancel() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                transformer.cancel();
            } catch (Exception ignored) {
            }
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Unable to delete cancelled compatibility output");
            }
        }
    }

    private CompatibilityVideoTranscoder() {}

    static Session start(
            Context context,
            Uri sourceUri,
            int sourceWidth,
            int sourceHeight,
            float sourceFrameRate,
            Callback callback
    ) {
        CompatibilityVideoPolicy.Target target =
                DeviceVideoCapabilities.chooseH264CompatibilityTarget(
                        sourceWidth,
                        sourceHeight,
                        sourceFrameRate
                );
        if (target == null) {
            callback.onError(
                    "No device-supported H.264 compatibility target was found.",
                    null
            );
            return null;
        }

        File cacheDir = chooseCacheDir(context);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            callback.onError("Unable to create compatibility cache.", null);
            return null;
        }
        cleanupOldFiles(cacheDir);

        File outputFile = new File(
                cacheDir,
                "compat_" + sourceKey(context, sourceUri) + "_"
                        + target.width + "x" + target.height + "_"
                        + Math.round(target.frameRate) + ".mp4"
        );
        if (isUsableVideo(outputFile)) {
            callback.onReady(outputFile, target, true);
            return null;
        }
        if (outputFile.exists() && !outputFile.delete()) {
            callback.onError("Unable to replace an invalid compatibility cache file.", null);
            return null;
        }

        ArrayList<Effect> videoEffects = new ArrayList<>();
        if (sourceFrameRate > 0f && target.frameRate > 0f
                && sourceFrameRate > target.frameRate + 1f) {
            // Drop frames before scaling so later GPU work is avoided for frames that will not be
            // encoded. This is only used when the device cannot decode a higher-rate target.
            videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(target.frameRate));
        }
        videoEffects.add(Presentation.createForWidthAndHeight(
                target.width,
                target.height,
                Presentation.LAYOUT_SCALE_TO_FIT
        ));

        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setRemoveAudio(true)
                .setEffects(new Effects(Collections.emptyList(), videoEffects))
                .build();

        final Session[] holder = new Session[1];
        Transformer transformer = new Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        Session session = holder[0];
                        if (session != null) {
                            session.markFinished();
                        }
                        if (isUsableVideo(outputFile)) {
                            callback.onReady(outputFile, target, false);
                        } else {
                            if (outputFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                outputFile.delete();
                            }
                            callback.onError(
                                    "Compatibility export completed without a usable video.",
                                    null
                            );
                        }
                    }

                    @Override
                    public void onError(
                            Composition composition,
                            ExportResult exportResult,
                            ExportException exportException
                    ) {
                        Session session = holder[0];
                        if (session != null) {
                            session.markFinished();
                        }
                        if (outputFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            outputFile.delete();
                        }
                        callback.onError("Compatibility export failed.", exportException);
                    }
                })
                .build();

        Session session = new Session(transformer, outputFile);
        holder[0] = session;
        try {
            transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            return session;
        } catch (RuntimeException e) {
            session.markFinished();
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            callback.onError("Unable to start compatibility export.", e);
            return null;
        }
    }

    private static File chooseCacheDir(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getCacheDir();
        }
        return new File(base, "compatible_video");
    }

    private static void cleanupOldFiles(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() > 0 && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static String sourceKey(Context context, Uri uri) {
        long length = -1L;
        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                length = descriptor.getLength();
            }
        } catch (Exception ignored) {
        }
        String material = uri.toString() + "|" + length;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                builder.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private static boolean isUsableVideo(File file) {
        if (file == null || !file.isFile() || file.length() < 4096L) {
            return false;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return parsePositive(width) && parsePositive(height) && parsePositive(duration);
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean parsePositive(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
