package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    // The adapter delegates "real" actions back to the activity so storage mutations,
    // navigation, and permission flows stay centralized in one place.
    public interface Listener {
        void onPlayVideo(VideoFile videoFile);
        void onRenameVideo(VideoFile videoFile);
        void onDeleteVideo(VideoFile videoFile);
    }

    private final Context context;
    private final List<VideoFile> videoFiles;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public VideoAdapter(Context context, List<VideoFile> videoFiles, Listener listener) {
        this.context = context;
        this.videoFiles = videoFiles;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        // Bind against a content Uri instead of a raw file path so the adapter continues to work
        // under scoped storage on Android 10+.
        VideoFile videoFile = videoFiles.get(position);
        Uri videoUri = videoFile.getContentUri();

        holder.videoName.setText(videoFile.getName());

        if (holder.videoProgress != null) {
            // The small progress bar in the thumbnail is purely library state. It is independent
            // from ExoPlayer and reads from our persisted playback progress cache.
            float fraction = PlaybackPrefs.getInstance(context).getProgressFraction(videoFile.getPlaybackKey());
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

        // Glide handles thumbnail extraction from the content Uri, while Palette gives each
        // card a subtle color identity based on the actual video frame.
        Glide.with(context)
                .asBitmap()
                .load(videoUri)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_video_placeholder)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                Transition<? super Bitmap> transition) {
                        holder.videoThumbnail.setImageBitmap(resource);

                        Palette.from(resource).generate(palette -> {
                            if (palette == null) return;

                            Palette.Swatch swatch = palette.getVibrantSwatch();
                            if (swatch == null) swatch = palette.getMutedSwatch();
                            if (swatch == null) swatch = palette.getDominantSwatch();
                            if (swatch == null) return;

                            int rgb = swatch.getRgb();
                            float[] hsv = new float[3];
                            Color.colorToHSV(rgb, hsv);
                            if (hsv[2] < 0.15f) return;

                            int r = Color.red(rgb);
                            int g = Color.green(rgb);
                            int b = Color.blue(rgb);
                            android.graphics.drawable.GradientDrawable gradient =
                                    new android.graphics.drawable.GradientDrawable(
                                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                                            new int[]{
                                                    Color.argb(60, r, g, b),
                                                    Color.argb(20, r, g, b),
                                                    Color.argb(0, r, g, b)
                                            }
                                    );
                            gradient.setCornerRadius(20 * context.getResources().getDisplayMetrics().density);
                            holder.cardTint.setBackground(gradient);
                        });
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                        holder.videoThumbnail.setImageDrawable(placeholder);
                        holder.cardTint.setBackgroundColor(Color.TRANSPARENT);
                    }
                });

        holder.videoSize.setText(getFileSize(videoFile.getSizeBytes()));

        // Metadata extraction is intentionally off the UI thread because MediaMetadataRetriever
        // can block on slower storage and would otherwise make scrolling feel heavy.
        executorService.execute(() -> {
            String duration = formatDuration(getVideoDuration(videoUri));
            String quality = "Unknown";

            try {
                Map<String, String> videoDetails = getVideoDetails(videoUri);
                quality = videoDetails.getOrDefault("Quality", quality);
            } catch (IOException e) {
                Log.e("VideoAdapter", "Error retrieving video details", e);
            }

            String finalQuality = quality;
            mainHandler.post(() -> {
                holder.videoDuration.setText(duration);
                holder.videoQuality.setText(finalQuality);
            });
        });

        // A normal tap means "open in player". The activity decides how to launch playback.
        holder.itemView.setOnClickListener(v -> listener.onPlayVideo(videoFile));

        // Long-press keeps secondary actions discoverable without overcrowding each card.
        holder.itemView.setOnLongClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.itemView, Gravity.END);
            popupMenu.getMenuInflater().inflate(R.menu.video_popup_menu, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.menu_delete) {
                    listener.onDeleteVideo(videoFile);
                    return true;
                } else if (itemId == R.id.menu_rename) {
                    listener.onRenameVideo(videoFile);
                    return true;
                } else if (itemId == R.id.menu_info) {
                    try {
                        showVideoInfo(videoFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                } else if (itemId == R.id.menu_share) {
                    shareVideo(videoFile);
                    return true;
                } else {
                    return false;
                }
            });
            popupMenu.show();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        android.widget.ImageView videoThumbnail;
        TextView videoName, videoSize, videoDuration, videoQuality;
        View cardTint;
        View videoProgress;

        @SuppressLint("WrongViewCast")
        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoThumbnail = itemView.findViewById(R.id.video_thumbnail);
            videoName = itemView.findViewById(R.id.video_name);
            videoSize = itemView.findViewById(R.id.video_size);
            videoDuration = itemView.findViewById(R.id.video_duration);
            videoQuality = itemView.findViewById(R.id.video_quality);
            cardTint = itemView.findViewById(R.id.card_tint);
            videoProgress = itemView.findViewById(R.id.video_progress);
        }
    }

    @SuppressLint("DefaultLocale")
    private String getFileSize(long sizeInBytes) {
        if (sizeInBytes <= 0) return "Unknown";
        if (sizeInBytes < 1024) return sizeInBytes + " B";
        int exp = (int) (Math.log(sizeInBytes) / Math.log(1024));
        String units = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", sizeInBytes / Math.pow(1024, exp), units);
    }

    private String getVideoDuration(Uri uri) {
        // MediaMetadataRetriever still works well here as a lightweight way to read duration
        // without having to prepare a player instance for each list row.
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return String.valueOf(duration != null ? Long.parseLong(duration) : 0);
        } catch (Exception e) {
            Log.e("VideoAdapter", "Error reading duration", e);
            return String.valueOf(0);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e("VideoAdapter", "Error releasing retriever", e);
            }
        }
    }

    private void showVideoInfo(VideoFile videoFile) throws IOException {
        // This dialog intentionally combines stored metadata (name, folder, size) with live
        // retriever data so the user gets a complete snapshot in one place.
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Video Information");
        StringBuilder info = new StringBuilder();
        info.append("Name: ").append(videoFile.getName()).append("\n");
        info.append("Uri: ").append(videoFile.getContentUri()).append("\n");
        if (videoFile.getFolderName() != null && !videoFile.getFolderName().isEmpty()) {
            info.append("Folder: ").append(videoFile.getFolderName()).append("\n");
        }
        info.append("Duration: ").append(formatDuration(getVideoDuration(videoFile.getContentUri()))).append("\n");
        info.append("Size: ").append(getFileSize(videoFile.getSizeBytes())).append("\n");
        Map<String, String> videoDetails = getVideoDetails(videoFile.getContentUri());
        info.append("Video Codec: ").append(videoDetails.get("Codec")).append("\n");
        info.append("Video Resolution: ").append(videoDetails.get("Resolution")).append("\n");
        info.append("Video Bitrate: ").append(videoDetails.get("Bitrate")).append("\n");
        builder.setMessage(info.toString());
        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    @SuppressLint("DefaultLocale")
    private Map<String, String> getVideoDetails(Uri uri) throws IOException {
        // The method returns strings rather than a dedicated model to keep the info dialog and
        // row binding code simple; this is just transient display data.
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Map<String, String> videoDetails = new HashMap<>();
        try {
            retriever.setDataSource(context, uri);
            String videoCodec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            videoDetails.put("Codec", videoCodec != null && videoCodec.contains("/")
                    ? videoCodec.split("/")[1]
                    : "Unknown");
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                int w = Integer.parseInt(width);
                int h = Integer.parseInt(height);
                videoDetails.put("Resolution", w + " x " + h);
                videoDetails.put("Quality", getQualityLabel(Math.min(w, h)));
            } else {
                videoDetails.put("Resolution", "Unknown");
                videoDetails.put("Quality", "Unknown");
            }
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                double bitrateMbps = Long.parseLong(bitrate) / 1_000_000.0;
                videoDetails.put("Bitrate", String.format("%.2f Mbps", bitrateMbps));
            } else {
                videoDetails.put("Bitrate", "Unknown");
            }
        } catch (Exception e) {
            Log.e("VideoAdapter", "Error reading details", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e("VideoAdapter", "Error releasing retriever", e);
            }
        }
        return videoDetails;
    }

    private String getQualityLabel(int height) {
        if (height >= 0 && height <= 180) return "144p";
        else if (height <= 280) return "240p";
        else if (height <= 400) return "360p";
        else if (height <= 500) return "480p";
        else if (height <= 800) return "720p";
        else if (height <= 1120) return "1080p";
        else if (height <= 1580) return "2K";
        else if (height <= 2400) return "4K";
        else return "4K+";
    }

    @SuppressLint("DefaultLocale")
    private String formatDuration(String duration) {
        if (duration == null) return "Unknown";
        long durationMs = Long.parseLong(duration);
        long hours = (durationMs / 1000) / 3600;
        long minutes = ((durationMs / 1000) % 3600) / 60;
        long seconds = (durationMs / 1000) % 60;
        if (hours > 0) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        else return String.format("%02d:%02d", minutes, seconds);
    }

    private void shareVideo(VideoFile videoFile) {
        // Sharing through the content Uri plus ClipData is the scoped-storage-safe way to give
        // another app temporary access to this media item.
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, videoFile.getContentUri());
        shareIntent.setClipData(ClipData.newUri(
                context.getContentResolver(),
                videoFile.getName(),
                videoFile.getContentUri()
        ));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(Intent.createChooser(shareIntent, "Share video via"));
        } catch (Exception e) {
            Toast.makeText(context, "No app available to share this video.", Toast.LENGTH_SHORT).show();
        }
    }
}
