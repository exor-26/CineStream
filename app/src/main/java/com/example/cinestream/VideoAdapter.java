package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private final Context context;
    private final List<VideoFile> videoFiles;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public VideoAdapter(Context context, List<VideoFile> videoFiles) {
        this.context = context;
        this.videoFiles = videoFiles;
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
        VideoFile videoFile = videoFiles.get(position);
        File video = new File(videoFile.getPath());

        holder.videoName.setText(videoFile.getName());

        if (holder.videoProgress != null) {
            float fraction = PlaybackPrefs.getInstance(context).getProgressFraction(videoFile.getPath());
            if (fraction > 0f) {
                holder.videoProgress.setVisibility(View.VISIBLE);
                holder.videoProgress.setPivotX(0f);       // scale from left edge
                holder.videoProgress.setScaleX(fraction); // 0.0–1.0 fills proportionally
            } else {
                holder.videoProgress.setVisibility(View.GONE);
                holder.videoProgress.setScaleX(1f);       // reset for recycled views
            }
        }

        // ── Reset tint before loading to avoid recycled colour bleed ──
        holder.cardTint.setBackgroundColor(Color.TRANSPARENT);

        // ── Load thumbnail + extract Palette for card tint ──
        Glide.with(context)
                .asBitmap()
                .load(video)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_video_placeholder)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                Transition<? super Bitmap> transition) {
                        // Set thumbnail
                        holder.videoThumbnail.setImageBitmap(resource);

                        // Extract dominant color and apply as subtle card tint
                        Palette.from(resource).generate(palette -> {
                            if (palette == null) return;

                            // Prefer vibrant → muted → dominant — avoids near-black on dark thumbnails
                            Palette.Swatch swatch = palette.getVibrantSwatch();
                            if (swatch == null) swatch = palette.getMutedSwatch();
                            if (swatch == null) swatch = palette.getDominantSwatch();
                            if (swatch == null) return;

                            int rgb = swatch.getRgb();

                            // Skip if colour is too dark (brightness < 40) — no point tinting with black
                            float[] hsv = new float[3];
                            Color.colorToHSV(rgb, hsv);
                            if (hsv[2] < 0.15f) return;

                            int r = Color.red(rgb);
                            int g = Color.green(rgb);
                            int b = Color.blue(rgb);
                            // Gradient: colour near thumbnail (left) → transparent (right)
                            android.graphics.drawable.GradientDrawable gradient =
                                    new android.graphics.drawable.GradientDrawable(
                                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                                            new int[]{
                                                    Color.argb(60, r, g, b),  // left — near thumbnail
                                                    Color.argb(20, r, g, b),  // mid — fading
                                                    Color.argb(0,  r, g, b)   // right — fully transparent
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

        holder.videoSize.setText(getFileSize(video.length()));

        // Retrieve video duration and quality asynchronously
        executorService.execute(() -> {
            String duration = formatDuration(getVideoDuration(video.getPath()));
            String quality = "Unknown";

            try {
                Map<String, String> videoDetails = getVideoDetails(video.getPath());
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

        holder.itemView.setOnClickListener(v -> {
            String videoPath = videoFile.getPath();
            Log.d("VideoAdapter", "Video path: " + videoPath);
            if (videoPath != null && !videoPath.isEmpty()) {
                Intent intent = new Intent(context, VideoPlayerActivity.class);
                intent.putExtra("VIDEO_PATH", videoPath);
                context.startActivity(intent);
            } else {
                Toast.makeText(context, "Video file path is invalid.", Toast.LENGTH_SHORT).show();
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.itemView, Gravity.END);
            popupMenu.getMenuInflater().inflate(R.menu.video_popup_menu, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.menu_delete) {
                    deleteVideo(videoFile);
                    return true;
                } else if (itemId == R.id.menu_rename) {
                    renameVideo(videoFile);
                    return true;
                } else if (itemId == R.id.menu_info) {
                    try { showVideoInfo(videoFile); } catch (IOException e) { throw new RuntimeException(e); }
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
        ImageView videoThumbnail;
        TextView videoName, videoSize, videoDuration, videoQuality;
        View cardTint; // ← tint layer reference
        View videoProgress;// ── NEW

        @SuppressLint("WrongViewCast")
        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoThumbnail = itemView.findViewById(R.id.video_thumbnail);
            videoName      = itemView.findViewById(R.id.video_name);
            videoSize      = itemView.findViewById(R.id.video_size);
            videoDuration  = itemView.findViewById(R.id.video_duration);
            videoQuality   = itemView.findViewById(R.id.video_quality);
            cardTint       = itemView.findViewById(R.id.card_tint); // ← new
            videoProgress  = itemView.findViewById(R.id.video_progress);
        }
    }

    // ── All helper methods below unchanged ────────────────────────────

    @SuppressLint("DefaultLocale")
    private String getFileSize(long sizeInBytes) {
        if (sizeInBytes < 1024) return sizeInBytes + " B";
        int exp = (int) (Math.log(sizeInBytes) / Math.log(1024));
        String units = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", sizeInBytes / Math.pow(1024, exp), units);
    }

    private String getVideoDuration(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return String.valueOf(duration != null ? Long.parseLong(duration) : 0);
        } catch (Exception e) {
            e.printStackTrace();
            return String.valueOf(0);
        } finally {
            try { retriever.release(); } catch (IOException e) { throw new RuntimeException(e); }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void deleteVideo(VideoFile videoFile) {
        new AlertDialog.Builder(context)
                .setTitle("Delete Video")
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    File file = new File(videoFile.getPath());
                    if (file.exists() && file.delete()) {
                        context.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                MediaStore.Video.Media.DATA + "=?", new String[]{videoFile.getPath()});
                        videoFiles.remove(videoFile);
                        notifyDataSetChanged();
                        Toast.makeText(context, "Video deleted and media store updated", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Failed to delete video", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void renameVideo(VideoFile videoFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Rename Video");
        final EditText input = new EditText(context);
        input.setText(videoFile.getName());
        builder.setView(input);
        builder.setPositiveButton("OK", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                String extension = videoFile.getPath().substring(videoFile.getPath().lastIndexOf('.'));
                if (!newName.endsWith(extension)) newName += extension;
                File oldFile = new File(videoFile.getPath());
                File newFile = new File(oldFile.getParent(), newName);
                if (newFile.exists()) {
                    Toast.makeText(context, "File already exists with the new name", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (oldFile.renameTo(newFile)) {
                    context.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Video.Media.DATA + "=?", new String[]{oldFile.getAbsolutePath()});
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.DATA, newFile.getAbsolutePath());
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, newFile.getName());
                    values.put(MediaStore.Video.Media.TITLE, newFile.getName());
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/" + extension.replace(".", ""));
                    context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    videoFile.setPath(newFile.getAbsolutePath());
                    videoFile.setName(newName);
                    MediaScannerConnection.scanFile(context, new String[]{newFile.getAbsolutePath()},
                            null, (path, uri) ->
                                    Toast.makeText(context, "Video renamed and refreshed successfully", Toast.LENGTH_SHORT).show());
                    notifyDataSetChanged();
                } else {
                    Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showVideoInfo(VideoFile videoFile) throws IOException {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Video Information");
        StringBuilder info = new StringBuilder();
        info.append("Name: ").append(videoFile.getName()).append("\n");
        info.append("Path: ").append(videoFile.getPath()).append("\n");
        info.append("Duration: ").append(formatDuration(getVideoDuration(videoFile.getPath()))).append("\n");
        Map<String, String> videoDetails = getVideoDetails(videoFile.getPath());
        info.append("Video Codec: ").append(videoDetails.get("Codec")).append("\n");
        info.append("Video Resolution: ").append(videoDetails.get("Resolution")).append("\n");
        info.append("Video Bitrate: ").append(videoDetails.get("Bitrate")).append("\n");
        Log.d("Video Info", info.toString());
        builder.setMessage(info.toString());
        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    @SuppressLint("DefaultLocale")
    private Map<String, String> getVideoDetails(String path) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Map<String, String> videoDetails = new HashMap<>();
        try {
            retriever.setDataSource(path);
            String videoCodec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            videoDetails.put("Codec", videoCodec != null ? videoCodec.split("/")[1] : "Unknown");
            String width  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
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
            e.printStackTrace();
        } finally {
            retriever.release();
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
        long hours   = (durationMs / 1000) / 3600;
        long minutes = ((durationMs / 1000) % 3600) / 60;
        long seconds = (durationMs / 1000) % 60;
        if (hours > 0) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        else           return String.format("%02d:%02d", minutes, seconds);
    }

    private void shareVideo(VideoFile videoFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(videoFile.getPath()));
        context.startActivity(Intent.createChooser(shareIntent, "Share video via"));
    }
}