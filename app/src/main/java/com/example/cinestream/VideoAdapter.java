package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    public interface Listener {
        void onPlayVideo(VideoFile videoFile, List<VideoFile> playlist, int position);
        void onRenameVideo(VideoFile videoFile);
        void onDeleteVideo(VideoFile videoFile);
    }

    private static final int ACTION_RENAME = 1;
    private static final int ACTION_SHARE  = 2;
    private static final int ACTION_DELETE = 3;
    private static final int ACTION_INFO   = 4;

    // All list/search/folder adapters share the same small worker pool and bounded caches.
    // This prevents each temporary adapter from creating its own threads and re-parsing the same
    // files when the user switches between library modes.
    private static final Handler MAIN_HANDLER = AppExecutors.mainHandler();
    private static final ExecutorService EXECUTOR = AppExecutors.metadata();
    private static final LruCache<String, MediaInfoSnapshot> MEDIA_INFO_CACHE = new LruCache<>(256);
    private static final LruCache<String, Integer> TINT_COLOR_CACHE = new LruCache<>(512);

    private final Context         context;
    private final List<VideoFile> videoFiles;
    private final Listener        listener;

    public VideoAdapter(Context context, List<VideoFile> videoFiles, Listener listener) {
        this.context    = context;
        this.videoFiles = videoFiles;
        this.listener   = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoFile videoFile = videoFiles.get(position);
        Uri       videoUri  = videoFile.getContentUri();
        String    playbackKey = videoFile.getPlaybackKey();

        // RecyclerView may reuse this holder while Glide is still decoding the previous video.
        // Cancel the stale request first so low-end devices do not keep decoding work that can no
        // longer become visible.
        if (holder.thumbnailTarget != null) {
            holder.boundPlaybackKey = null;
            Glide.with(holder.itemView).clear(holder.thumbnailTarget);
            holder.thumbnailTarget = null;
        }

        holder.boundPlaybackKey = playbackKey;
        holder.videoName.setText(videoFile.getName());

        // ── Playback progress bar ────────────────────────────────────
        if (holder.videoProgress != null) {
            float fraction = PlaybackPrefs.getInstance(context)
                    .getProgressFraction(videoFile.getPlaybackKey());
            if (fraction > 0f) {
                holder.videoProgress.setVisibility(View.VISIBLE);
                holder.videoProgress.setPivotX(0f);
                holder.videoProgress.setScaleX(fraction);
            } else {
                holder.videoProgress.setVisibility(View.GONE);
                holder.videoProgress.setScaleX(1f);
            }
        }

        holder.cardTint.setBackgroundColor(Color.TRANSPARENT);
        holder.videoDuration.setText("00:00");
        holder.videoQuality.setText("Unknown");
        applyCachedTint(holder, playbackKey);

        // ── Thumbnail + Palette tint ─────────────────────────────────
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource,
                                        Transition<? super Bitmap> transition) {
                if (!playbackKey.equals(holder.boundPlaybackKey)) {
                    return;
                }
                holder.videoThumbnail.setImageBitmap(resource);
                Integer cachedTint = TINT_COLOR_CACHE.get(playbackKey);
                if (cachedTint != null) {
                    applyGradientTint(holder, cachedTint);
                    return;
                }
                Palette.from(resource).generate(palette -> {
                    if (!playbackKey.equals(holder.boundPlaybackKey)) {
                        return;
                    }
                    if (palette == null) return;
                    Palette.Swatch swatch = palette.getVibrantSwatch();
                    if (swatch == null) swatch = palette.getMutedSwatch();
                    if (swatch == null) swatch = palette.getDominantSwatch();
                    if (swatch == null) return;
                    int     rgb = swatch.getRgb();
                    float[] hsv = new float[3];
                    Color.colorToHSV(rgb, hsv);
                    if (hsv[2] < 0.15f) return;
                    TINT_COLOR_CACHE.put(playbackKey, rgb);
                    applyGradientTint(holder, rgb);
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                if (playbackKey.equals(holder.boundPlaybackKey)) {
                    holder.videoThumbnail.setImageDrawable(placeholder);
                    applyCachedTint(holder, playbackKey);
                }
            }
        };
        holder.thumbnailTarget = target;

        Glide.with(context)
                .asBitmap()
                .load(videoUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(336, 216)
                .centerCrop()
                .placeholder(R.drawable.ic_video_placeholder)
                .into(target);

        holder.videoSize.setText(getFileSize(videoFile.getSizeBytes()));

        // Click handlers must be attached for every bind. If they live below the metadata-cache
        // fast path, recycled rows can lose their play/open behavior after rebinding.
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return;
            }
            listener.onPlayVideo(videoFile, videoFiles, adapterPosition);
        });

        holder.itemView.setOnLongClickListener(v -> {
            List<GlassUi.ActionItem> actions = new ArrayList<>();
            actions.add(new GlassUi.ActionItem(ACTION_RENAME, "Rename",
                    "Update the media title shown by the system library"));
            actions.add(new GlassUi.ActionItem(ACTION_SHARE, "Share",
                    "Send the video through a scoped-storage safe share intent"));
            actions.add(new GlassUi.ActionItem(ACTION_DELETE, "Delete",
                    "Remove the media item from device storage"));
            actions.add(new GlassUi.ActionItem(ACTION_INFO, "Detailed info",
                    "Inspect container, codecs, bitrate, resolution, and more"));

            GlassUi.showActionSheet(context, videoFile.getName(), actions, item -> {
                if (item.id == ACTION_DELETE) {
                    listener.onDeleteVideo(videoFile);
                } else if (item.id == ACTION_RENAME) {
                    listener.onRenameVideo(videoFile);
                } else if (item.id == ACTION_INFO) {
                    // Off main thread — avoids ANR on large files.
                    EXECUTOR.execute(() -> {
                        MediaInfoSnapshot snapshot = extractMediaInfo(videoFile, true);
                        MAIN_HANDLER.post(() -> showVideoInfo(videoFile, snapshot));
                    });
                } else if (item.id == ACTION_SHARE) {
                    shareVideo(videoFile);
                }
            });
            return true;
        });

        // ── Metadata extraction — off UI thread ──────────────────────
        MediaInfoSnapshot cachedSnapshot = MEDIA_INFO_CACHE.get(playbackKey);
        if (cachedSnapshot != null) {
            holder.videoDuration.setText(cachedSnapshot.durationLabel);
            holder.videoQuality.setText(cachedSnapshot.qualityLabel);
            return;
        }

        EXECUTOR.execute(() -> {
            MediaInfoSnapshot snapshot = extractMediaInfo(videoFile, false);
            MAIN_HANDLER.post(() -> {
                MEDIA_INFO_CACHE.put(playbackKey, snapshot);
                if (!playbackKey.equals(holder.boundPlaybackKey)) {
                    return;
                }
                holder.videoDuration.setText(snapshot.durationLabel);
                holder.videoQuality.setText(snapshot.qualityLabel);
            });
        });
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        holder.boundPlaybackKey = null;
        if (holder.thumbnailTarget != null) {
            Glide.with(holder.itemView).clear(holder.thumbnailTarget);
            holder.thumbnailTarget = null;
        }
        holder.videoThumbnail.setImageResource(R.drawable.ic_video_placeholder);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    // ── ViewHolder ───────────────────────────────────────────────────

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        android.widget.ImageView videoThumbnail;
        TextView videoName, videoSize, videoDuration, videoQuality;
        View cardTint;
        View videoProgress;
        String boundPlaybackKey;
        CustomTarget<Bitmap> thumbnailTarget;

        @SuppressLint("WrongViewCast")
        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoThumbnail = itemView.findViewById(R.id.video_thumbnail);
            videoName      = itemView.findViewById(R.id.video_name);
            videoSize      = itemView.findViewById(R.id.video_size);
            videoDuration  = itemView.findViewById(R.id.video_duration);
            videoQuality   = itemView.findViewById(R.id.video_quality);
            cardTint       = itemView.findViewById(R.id.card_tint);
            videoProgress  = itemView.findViewById(R.id.video_progress);
        }
    }

    private void applyCachedTint(VideoViewHolder holder, String playbackKey) {
        Integer cachedTint = TINT_COLOR_CACHE.get(playbackKey);
        if (cachedTint == null) {
            holder.cardTint.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        applyGradientTint(holder, cachedTint);
    }

    private void applyGradientTint(VideoViewHolder holder, int rgb) {
        int r = Color.red(rgb), g = Color.green(rgb), b = Color.blue(rgb);
        android.graphics.drawable.GradientDrawable gradient =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{
                                Color.argb(60, r, g, b),
                                Color.argb(20, r, g, b),
                                Color.argb(0,  r, g, b)
                        });
        gradient.setCornerRadius(
                20 * context.getResources().getDisplayMetrics().density);
        holder.cardTint.setBackground(gradient);
    }

    // ── Media info snapshot ──────────────────────────────────────────

    private static final class MediaInfoSnapshot {
        String durationLabel     = "00:00";
        String qualityLabel      = "Unknown";
        String resolutionLabel   = "Unknown";
        String containerLabel    = "Unknown";
        String videoCodecLabel   = "Unknown";
        String audioCodecLabel   = "Unknown";
        String bitrateLabel      = "Unknown";
        String fileSizeLabel     = "Unknown";
        String folderLabel       = "Unknown";
        String frameRateLabel    = "Unknown";
        String audioDetailsLabel = "Unknown";
        String uriLabel          = "Unknown";
        int primaryAudioChannels   = 0;
        int primaryAudioSampleRate = 0;
        List<String> allAudioTrackDetails = new ArrayList<>();
    }

    // ── Core extraction ──────────────────────────────────────────────

    private MediaInfoSnapshot extractMediaInfo(VideoFile videoFile, boolean allowFfmpegFallback) {
        MediaInfoSnapshot      snapshot  = new MediaInfoSnapshot();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaExtractor         extractor = new MediaExtractor();

        snapshot.fileSizeLabel = getFileSize(videoFile.getSizeBytes());
        snapshot.folderLabel   = videoFile.getFolderName() != null
                ? videoFile.getFolderName() : "Unknown";
        snapshot.uriLabel      = videoFile.getContentUri().toString();

        try {
            Uri uri = videoFile.getContentUri();
            retriever.setDataSource(context, uri);
            extractor.setDataSource(context, uri, null);

            // Duration
            snapshot.durationLabel = formatDuration(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));

            boolean hasAudio = "yes".equalsIgnoreCase(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));

            String containerMime = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            snapshot.containerLabel = prettifyContainer(containerMime, videoFile.getName());

            // Base resolution
            int width    = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height   = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation == 90 || rotation == 270) {
                int t = width; width = height; height = t;
            }

            long overallBitrate = parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE));
            if (overallBitrate > 0) snapshot.bitrateLabel = formatBitrate(overallBitrate);

            // Per-track scan
            // Pass 1 — video track info
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME)
                        ? format.getString(MediaFormat.KEY_MIME) : null;
                if (mime == null || !mime.startsWith("video/")) continue;

                snapshot.videoCodecLabel = prettifyCodec(mime);
                if (format.containsKey(MediaFormat.KEY_WIDTH))
                    width  = format.getInteger(MediaFormat.KEY_WIDTH);
                if (format.containsKey(MediaFormat.KEY_HEIGHT))
                    height = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    int r = format.getInteger(MediaFormat.KEY_ROTATION);
                    if (r == 90 || r == 270) { int t = width; width = height; height = t; }
                }
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    snapshot.frameRateLabel = format.getInteger(MediaFormat.KEY_FRAME_RATE) + " fps";
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    String cr = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                    if (cr != null && !cr.isEmpty()) {
                        try {
                            snapshot.frameRateLabel = String.format(
                                    Locale.US, "%.0f fps", Float.parseFloat(cr));
                        } catch (Exception ignored) {}
                    }
                }
                if (format.containsKey(MediaFormat.KEY_BIT_RATE))
                    snapshot.bitrateLabel = formatBitrate(format.getInteger(MediaFormat.KEY_BIT_RATE));
            }

            // Pass 2 — audio tracks, aggressive: don't skip null mime tracks
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME)
                        ? format.getString(MediaFormat.KEY_MIME) : null;

                // Skip anything that is clearly video or subtitle
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("text/")
                        || mime.startsWith("application/"))) continue;

                // Channel + sample — available even when mime is null/unsupported
                int ch = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                int hz = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;

                // Skip if nothing useful at all
                if (mime == null && ch == 0 && hz == 0) continue;

                String codecLabel = prettifyCodec(mime); // "Unknown" if mime null

                List<String> parts = new ArrayList<>();
                if (!"Unknown".equals(codecLabel)) parts.add(codecLabel);
                if (ch > 0) parts.add(ch + " ch");
                if (hz > 0) parts.add(hz + " Hz");
                String trackDetail = parts.isEmpty() ? "Audio track" : TextUtils.join(" • ", parts);
                snapshot.allAudioTrackDetails.add(trackDetail);

                if ("Unknown".equals(snapshot.audioCodecLabel)) {
                    snapshot.audioCodecLabel        = codecLabel;
                    snapshot.primaryAudioChannels   = ch;
                    snapshot.primaryAudioSampleRate = hz;
                }
            }

            // Build multi-track audio details label
            if (!snapshot.allAudioTrackDetails.isEmpty()) {
                if (snapshot.allAudioTrackDetails.size() == 1) {
                    snapshot.audioDetailsLabel = snapshot.allAudioTrackDetails.get(0);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < snapshot.allAudioTrackDetails.size(); i++) {
                        sb.append("Track ").append(i + 1).append(": ")
                                .append(snapshot.allAudioTrackDetails.get(i));
                        if (i < snapshot.allAudioTrackDetails.size() - 1) sb.append("\n");
                    }
                    snapshot.audioDetailsLabel = sb.toString();
                }
            }

            // Audio fallback chain
            if (hasAudio && "Unknown".equals(snapshot.audioCodecLabel)) {

                // 1. Filename keyword hints
                String fileHint    = fallbackAudioCodec(videoFile.getName(), containerMime);
                boolean hintUseful = !fileHint.equals("Detected but not reported")
                        && !fileHint.contains("unsupported");

                if (hintUseful) {
                    snapshot.audioCodecLabel = fileHint;

                } else if ("MKV".equals(snapshot.containerLabel)
                        || "video/x-matroska".equals(containerMime)) {

                    // 2. Raw EBML byte scan — most reliable for E-AC-3/DTS/TrueHD in MKV
                    String scanned = scanMkvAudioCodec(videoFile);
                    snapshot.audioCodecLabel = scanned != null ? scanned : fileHint;

                } else {
                    snapshot.audioCodecLabel = fileHint;
                }
            }

            // 3. Second extractor pass — KEY_CODECS_STRING on API 29+
            if ("Unknown".equals(snapshot.audioCodecLabel)
                    || "Detected but not reported".equals(snapshot.audioCodecLabel)) {
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.containsKey(MediaFormat.KEY_MIME)
                            ? format.getString(MediaFormat.KEY_MIME) : null;
                    if (!looksLikeAudioTrack(mime, format)) continue;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && format.containsKey(MediaFormat.KEY_CODECS_STRING)) {
                        String cs = format.getString(MediaFormat.KEY_CODECS_STRING);
                        if (cs != null && !cs.isEmpty()) {
                            snapshot.audioCodecLabel   = cs.toUpperCase(Locale.US);
                            snapshot.audioDetailsLabel = buildAudioDetails(format);
                            break;
                        }
                    }
                    snapshot.audioDetailsLabel = buildAudioDetails(format);
                    if ("Unknown".equals(snapshot.audioCodecLabel))
                        snapshot.audioCodecLabel = "Audio (unsupported by system parser)";
                    break;
                }
            }

            if (hasAudio && "Unknown".equals(snapshot.audioDetailsLabel))
                snapshot.audioDetailsLabel = "Audio track present";

            // Resolution + quality
            if (width > 0 && height > 0) {
                snapshot.resolutionLabel = String.format(Locale.US, "%d x %d", width, height);
                snapshot.qualityLabel    = getQualityLabel(width, height);
            }

        } catch (Exception e) {
            Log.e("VideoAdapter", "extractMediaInfo failed", e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
        // FFmpeg metadata probing is kept out of normal row binding because some files trigger
        // a native crash in the retriever library during fast RecyclerView rebinding.
        if (allowFfmpegFallback && shouldUseFfmpegFallback(videoFile, snapshot)) {
            fillUnknownsWithFfmpeg(videoFile, snapshot);
        }

        return snapshot;
    }

    // ── Raw EBML scan for MKV audio codec IDs ───────────────────────

    private String scanMkvAudioCodec(VideoFile videoFile) {
        try (java.io.InputStream is = context.getContentResolver()
                .openInputStream(videoFile.getContentUri())) {
            if (is == null) return null;

            // 256 KB covers even large MKV headers with many tracks
            byte[] buf  = new byte[262144];
            int    read = 0, chunk;
            while (read < buf.length
                    && (chunk = is.read(buf, read, buf.length - read)) != -1) {
                read += chunk;
            }

            String raw = new String(buf, 0, read,
                    java.nio.charset.StandardCharsets.ISO_8859_1);

            // Ordered most-specific first to avoid A_AC3 matching before A_EAC3
            if (raw.contains("A_TRUEHD"))  return "TrueHD";
            if (raw.contains("A_EAC3"))    return "E-AC-3";
            if (raw.contains("A_AC3"))     return "AC-3";
            if (raw.contains("A_DTS"))     return "DTS";
            if (raw.contains("A_AAC"))     return "AAC";
            if (raw.contains("A_FLAC"))    return "FLAC";
            if (raw.contains("A_OPUS"))    return "Opus";
            if (raw.contains("A_VORBIS"))  return "Vorbis";
            if (raw.contains("A_MPEG/L3")) return "MP3";
            if (raw.contains("A_PCM"))     return "PCM";

        } catch (Exception e) {
            Log.e("VideoAdapter", "MKV byte scan failed", e);
        }
        return null;
    }

    // ── Info dialog ──────────────────────────────────────────────────

    private void showVideoInfo(VideoFile videoFile, MediaInfoSnapshot snapshot) {
        List<GlassUi.InfoItem> rows = new ArrayList<>();
        rows.add(new GlassUi.InfoItem("Name",            videoFile.getName()));
        rows.add(new GlassUi.InfoItem("Container",       snapshot.containerLabel));
        rows.add(new GlassUi.InfoItem("Video codec",     snapshot.videoCodecLabel));
        rows.add(new GlassUi.InfoItem("Audio codec",     snapshot.audioCodecLabel));
        rows.add(new GlassUi.InfoItem("Resolution",      snapshot.resolutionLabel));
        rows.add(new GlassUi.InfoItem("Display quality", snapshot.qualityLabel));
        rows.add(new GlassUi.InfoItem("Duration",        snapshot.durationLabel));
        rows.add(new GlassUi.InfoItem("Bitrate",         snapshot.bitrateLabel));
        rows.add(new GlassUi.InfoItem("Frame rate",      snapshot.frameRateLabel));
        rows.add(new GlassUi.InfoItem("Audio details",   snapshot.audioDetailsLabel));
        rows.add(new GlassUi.InfoItem("File size",       snapshot.fileSizeLabel));
        rows.add(new GlassUi.InfoItem("Folder",          snapshot.folderLabel));
        rows.add(new GlassUi.InfoItem("Content Uri",     snapshot.uriLabel));
        GlassUi.showInfoDialog(context, "Detailed media info", rows);
    }

    // ── Audio helpers ────────────────────────────────────────────────

    private String buildAudioDetails(MediaFormat format) {
        List<String> parts = new ArrayList<>();
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            String label = prettifyCodec(format.getString(MediaFormat.KEY_MIME));
            if (!"Unknown".equals(label)) parts.add(label);
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            parts.add(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + " ch");
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            parts.add(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) + " Hz");
        return parts.isEmpty() ? "Unknown" : TextUtils.join(" • ", parts);
    }

    private boolean looksLikeAudioTrack(String mime, MediaFormat format) {
        if (mime != null && mime.startsWith("audio/")) return true;
        return format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                || format.containsKey(MediaFormat.KEY_SAMPLE_RATE);
    }

    private String fallbackAudioCodec(String fileName, String containerMime) {
        String n = fileName.toLowerCase(Locale.US);
        if (n.contains("truehd")  || n.contains("true-hd"))                   return "TrueHD";
        if (n.contains("eac3")    || n.contains("ec3") || n.contains("e-ac-3")
                || n.contains("ddplus") || n.contains("dd+"))                  return "E-AC-3";
        if (n.contains("ac3")     || n.contains("dolby digital")
                || n.contains(" dd ")   || n.contains(".dd."))                 return "AC-3";
        if (n.contains("dts-hd")  || n.contains("dtshd"))                     return "DTS-HD";
        if (n.contains("dts"))                                                 return "DTS";
        if (n.contains("aac"))                                                 return "AAC";
        if (n.contains("flac"))                                                return "FLAC";
        if (n.contains("opus"))                                                return "Opus";
        if ("video/x-matroska".equals(containerMime))
            return "Audio (MKV — unsupported by system parser)";
        if ("video/mp4".equals(containerMime))
            return "AAC (assumed)";
        return "Detected but not reported";
    }

    // ── Utility ──────────────────────────────────────────────────────

    @SuppressLint("DefaultLocale")
    private String getFileSize(long bytes) {
        if (bytes <= 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        int    exp   = (int) (Math.log(bytes) / Math.log(1024));
        String units = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, exp), units);
    }

    private int parseInt(String v) {
        if (v == null || v.isEmpty()) return 0;
        try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
    }

    private long parseLong(String v) {
        if (v == null || v.isEmpty()) return 0L;
        try { return Long.parseLong(v); } catch (Exception e) { return 0L; }
    }

    private String formatBitrate(long bps) {
        if (bps <= 0) return "Unknown";
        double mbps = bps / 1_000_000.0;
        return mbps >= 1d
                ? String.format(Locale.US, "%.2f Mbps", mbps)
                : String.format(Locale.US, "%.0f kbps", bps / 1000.0);
    }

    @SuppressLint("DefaultLocale")
    private String formatDuration(String duration) {
        if (duration == null) return "Unknown";
        long ms      = parseLong(duration);
        long hours   = (ms / 1000) / 3600;
        long minutes = ((ms / 1000) % 3600) / 60;
        long seconds = (ms / 1000) % 60;
        return hours > 0
                ? String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private String prettifyContainer(String mime, String fileName) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1)
            ext = fileName.substring(dot + 1).toUpperCase(Locale.US);
        if (mime == null || mime.isEmpty()) return ext.isEmpty() ? "Unknown" : ext;
        switch (mime) {
            case "video/x-matroska": case "audio/x-matroska": return "MKV";
            case "video/mp4":        case "audio/mp4":         return "MP4";
            case "video/webm":                                 return "WEBM";
            case "video/3gpp":                                 return "3GP";
            default:
                return ext.isEmpty()
                        ? mime.replace("video/", "").replace("audio/", "")
                        .toUpperCase(Locale.US)
                        : ext;
        }
    }

    private void fillUnknownsWithFfmpeg(VideoFile videoFile, MediaInfoSnapshot snapshot) {
        wseemann.media.FFmpegMediaMetadataRetriever ff =
                new wseemann.media.FFmpegMediaMetadataRetriever();
        try {
            ff.setDataSource(context, videoFile.getContentUri());

            // Audio codec
            if ("Unknown".equals(snapshot.audioCodecLabel)
                    || snapshot.audioCodecLabel.contains("unsupported")
                    || snapshot.audioCodecLabel.contains("parser")
                    || snapshot.audioCodecLabel.contains("MKV")) {
                String codec = ff.extractMetadata(
                        wseemann.media.FFmpegMediaMetadataRetriever.METADATA_KEY_AUDIO_CODEC);
                if (codec != null && !codec.isEmpty()) {
                    snapshot.audioCodecLabel = codec.toUpperCase(Locale.US);

                    List<String> parts = new ArrayList<>();
                    parts.add(codec.toUpperCase(Locale.US));
                    if (snapshot.primaryAudioChannels   > 0) parts.add(snapshot.primaryAudioChannels   + " ch");
                    if (snapshot.primaryAudioSampleRate > 0) parts.add(snapshot.primaryAudioSampleRate + " Hz");
                    String enriched = TextUtils.join(" • ", parts);

                    if (snapshot.allAudioTrackDetails.size() > 1) {
                        snapshot.allAudioTrackDetails.set(0, enriched);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < snapshot.allAudioTrackDetails.size(); i++) {
                            sb.append("Track ").append(i + 1).append(": ")
                                    .append(snapshot.allAudioTrackDetails.get(i));
                            if (i < snapshot.allAudioTrackDetails.size() - 1) sb.append("\n");
                        }
                        snapshot.audioDetailsLabel = sb.toString();
                    } else {
                        snapshot.audioDetailsLabel = enriched;
                    }
                }
            }

            // Frame rate
            if ("Unknown".equals(snapshot.frameRateLabel)) {
                String fps = ff.extractMetadata(
                        wseemann.media.FFmpegMediaMetadataRetriever.METADATA_KEY_FRAMERATE);
                if (fps != null && !fps.isEmpty()) {
                    try {
                        if (fps.contains("/")) {
                            String[] p = fps.split("/");
                            float f = Float.parseFloat(p[0]) / Float.parseFloat(p[1]);
                            snapshot.frameRateLabel = String.format(Locale.US, "%.2f fps", f);
                        } else {
                            snapshot.frameRateLabel = fps + " fps";
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Video codec fallback
            if ("Unknown".equals(snapshot.videoCodecLabel)) {
                String codec = ff.extractMetadata(
                        wseemann.media.FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC);
                if (codec != null && !codec.isEmpty()) {
                    snapshot.videoCodecLabel = codec.toUpperCase(Locale.US);
                }
            }

        } catch (Exception e) {
            Log.e("VideoAdapter", "FFmpeg metadata failed", e);
        } finally {
            try { ff.release(); } catch (Exception ignored) {}
        }
    }

    private boolean shouldUseFfmpegFallback(VideoFile videoFile, MediaInfoSnapshot snapshot) {
        String container = snapshot.containerLabel != null
                ? snapshot.containerLabel.toUpperCase(Locale.US) : "";
        String lowerName = videoFile.getName() != null
                ? videoFile.getName().toLowerCase(Locale.US) : "";
        String codec = snapshot.audioCodecLabel != null
                ? snapshot.audioCodecLabel.toLowerCase(Locale.US) : "";

        // Phone-recorded MP4/3GP files are the crash-prone path in logcat; skip FFmpeg there.
        if ("MP4".equals(container) || "3GP".equals(container)) {
            return lowerName.contains("eac3")
                    || lowerName.contains("ec3")
                    || lowerName.contains("ac3")
                    || lowerName.contains("dts")
                    || lowerName.contains("truehd");
        }

        if ("MKV".equals(container) || "WEBM".equals(container)) {
            return true;
        }

        return "unknown".equals(codec)
                || codec.contains("unsupported")
                || codec.contains("parser")
                || codec.contains("mkv")
                || lowerName.contains("eac3")
                || lowerName.contains("ec3")
                || lowerName.contains("ac3")
                || lowerName.contains("dts")
                || lowerName.contains("truehd");
    }

    private String prettifyCodec(String mime) {
        if (mime == null || mime.isEmpty()) return "Unknown";
        switch (mime) {
            case "video/avc":            return "AVC (H.264)";
            case "video/hevc":           return "HEVC (H.265)";
            case "video/mp4v-es":        return "MPEG-4 Visual";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "video/av01":           return "AV1";
            case "audio/mp4a-latm":      return "AAC";
            case "audio/ac3":            return "AC-3";
            case "audio/eac3":           return "E-AC-3";
            case "audio/eac3-joc":       return "E-AC-3 JOC";
            case "audio/ac4":            return "AC-4";
            case "audio/vnd.dts":
            case "audio/vnd.dts.hd":     return "DTS";
            case "audio/true-hd":        return "TrueHD";
            case "audio/opus":           return "Opus";
            case "audio/vorbis":         return "Vorbis";
            case "audio/mpeg":           return "MP3";
            case "audio/flac":           return "FLAC";
            default:
                String n = mime.contains("/")
                        ? mime.substring(mime.indexOf('/') + 1) : mime;
                return n.toUpperCase(Locale.US);
        }
    }

    private String getQualityLabel(int w, int h) {
        int d = Math.max(w, h);
        if (d >= 3840) return "4K";
        if (d >= 2560) return "2K";
        if (d >= 1920) return "1080p";
        if (d >= 1280) return "720p";
        if (d >= 854)  return "480p";
        if (d >= 640)  return "360p";
        if (d >= 426)  return "240p";
        return "144p";
    }

    // ── Share ────────────────────────────────────────────────────────

    private void shareVideo(VideoFile videoFile) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_STREAM, videoFile.getContentUri());
        intent.setClipData(ClipData.newUri(
                context.getContentResolver(),
                videoFile.getName(),
                videoFile.getContentUri()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(Intent.createChooser(intent, "Share video via"));
        } catch (Exception e) {
            GlassUi.showToast(context, "No app available to share this video.");
        }
    }
}
