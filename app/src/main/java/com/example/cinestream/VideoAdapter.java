package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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
import android.view.MenuItem;
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
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

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
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(); //

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
        File video = new File(videoFile.getPath()); // Create File object once

        // Set video name and load thumbnail using Glide
        holder.videoName.setText(videoFile.getName());
        Glide.with(context)
                .load(video)
                .placeholder(R.drawable.ic_video_placeholder)
                .into(holder.videoThumbnail);

        // Set video size
        holder.videoSize.setText(getFileSize(video.length()));

        // Retrieve video duration and quality asynchronously
        executorService.execute(() -> {
            String duration = formatDuration(getVideoDuration(video.getPath()));
            String quality = "Unknown Quality"; // Default value

            try {
                Map<String, String> videoDetails = getVideoDetails(video.getPath());
                quality = videoDetails.getOrDefault("Quality", quality);
            } catch (IOException e) {
                Log.e("VideoAdapter", "Error retrieving video details", e); // Log the error
            }

            // Update UI on the main thread
            String finalQuality = quality;
            mainHandler.post(() -> {
                holder.videoDuration.setText(duration);
                holder.videoQuality.setText(finalQuality);
            });
        });

        // In VideoAdapter's onBindViewHolder
        holder.itemView.setOnClickListener(v -> {
            // Get the video path and print it for debugging
            String videoPath = videoFile.getPath();
            Log.d("VideoAdapter", "Video path: " + videoPath);

            // Ensure video path is valid before launching
            if (videoPath != null && !videoPath.isEmpty()) {
                Intent intent = new Intent(context, VideoPlayerActivity.class);
                intent.putExtra("VIDEO_PATH", videoPath);
                context.startActivity(intent);
            } else {
                Toast.makeText(context, "Video file path is invalid.", Toast.LENGTH_SHORT).show();
            }
        });

        // Add long click listener for showing the popup menu on right side
        holder.itemView.setOnLongClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.itemView, Gravity.END);  // Set Gravity.END to show on the right
            popupMenu.getMenuInflater().inflate(R.menu.video_popup_menu, popupMenu.getMenu());

            // Set item click listeners for the popup menu
            popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    int itemId = item.getItemId();  // Get the item ID of the clicked menu item

                    if (itemId == R.id.menu_delete) {
                        // Handle delete action
                        deleteVideo(videoFile);
                        return true;
                    } else if (itemId == R.id.menu_rename) {
                        // Handle rename action
                        renameVideo(videoFile);
                        return true;
                    } else if (itemId == R.id.menu_info) {
                        // Handle information action
                        try {
                            showVideoInfo(videoFile);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return true;
                    } else if (itemId == R.id.menu_share) {
                        // Handle share action
                        shareVideo(videoFile);
                        return true;
                    } else {
                        return false;
                    }
                }
            });

            // Show the popup menu
            popupMenu.show();
            return true; // Return true to indicate that the long click was handled
        });
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        ImageView videoThumbnail;
        TextView videoName, videoSize, videoDuration, videoQuality;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoThumbnail = itemView.findViewById(R.id.video_thumbnail);
            videoName = itemView.findViewById(R.id.video_name);
            videoSize = itemView.findViewById(R.id.video_size);
            videoDuration = itemView.findViewById(R.id.video_duration);
            videoQuality = itemView.findViewById(R.id.video_quality); // Replace with your actual TextView IDs
        }
    }
    // Helper method to get video size
        @SuppressLint("DefaultLocale")
        private String getFileSize(long sizeInBytes) {
        if (sizeInBytes < 1024) return sizeInBytes + " B";
        int exp = (int) (Math.log(sizeInBytes) / Math.log(1024));
        String units = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", sizeInBytes / Math.pow(1024, exp), units);
    }

    // Helper method to fetch video duration with exception handling
        private String getVideoDuration(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return String.valueOf(duration != null ? Long.parseLong(duration) : 0);
        } catch (Exception e) {
            e.printStackTrace();
            return String.valueOf(0); // Return 0 if an error occurs
        } finally {
            try {
                retriever.release(); // Ensures release is called regardless of exceptions
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void deleteVideo(VideoFile videoFile) {
        // Create an AlertDialog to confirm deletion
        new AlertDialog.Builder(context)
                .setTitle("Delete Video")
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    File file = new File(videoFile.getPath());
                    if (file.exists() && file.delete()) {
                        // Notify MediaStore about the deletion
                        context.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                MediaStore.Video.Media.DATA + "=?", new String[]{videoFile.getPath()});

                        videoFiles.remove(videoFile);
                        notifyDataSetChanged();
                        Toast.makeText(context, "Video deleted and media store updated", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Failed to delete video", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("No", null) // Dismiss the dialog on "No"
                .show();
    }

    // Method to rename video
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
                if (!newName.endsWith(extension)) {
                    newName += extension;
                }

                File oldFile = new File(videoFile.getPath());
                File newFile = new File(oldFile.getParent(), newName);

                if (newFile.exists()) {
                    Toast.makeText(context, "File already exists with the new name", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (oldFile.renameTo(newFile)) {
                    // Remove old file from MediaStore
                    context.getContentResolver().delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Video.Media.DATA + "=?", new String[]{oldFile.getAbsolutePath()});

                    // Insert new file into MediaStore
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.DATA, newFile.getAbsolutePath());
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, newFile.getName());
                    values.put(MediaStore.Video.Media.TITLE, newFile.getName());
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/" + extension.replace(".", ""));
                    context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                    // Update the videoFile object with the new path and name
                    videoFile.setPath(newFile.getAbsolutePath());
                    videoFile.setName(newName);

                    // Use MediaScanner to refresh file metadata
                    MediaScannerConnection.scanFile(context, new String[]{newFile.getAbsolutePath()},
                            null, (path, uri) -> {
                                // File scanned successfully
                                Toast.makeText(context, "Video renamed and refreshed successfully", Toast.LENGTH_SHORT).show();
                            });

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

    // Method to show video information
    private void showVideoInfo(VideoFile videoFile) throws IOException {
        // Create an AlertDialog to display video information
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Video Information");

        // Prepare video details
        StringBuilder info = new StringBuilder();
        info.append("Name: ").append(videoFile.getName()).append("\n");
        info.append("Path: ").append(videoFile.getPath()).append("\n");

        // Add additional details (dummy values for example)
        info.append("Duration: ").append(formatDuration(getVideoDuration(videoFile.getPath()))).append("\n");
        // Call the method to get video details
        Map<String, String> videoDetails = getVideoDetails(videoFile.getPath());

        // Append video details to the info StringBuilder
        info.append("Video Codec: ").append(videoDetails.get("Codec")).append("\n");
        info.append("Video Resolution: ").append(videoDetails.get("Resolution")).append("\n");
        info.append("Video Bitrate: ").append(videoDetails.get("Bitrate")).append("\n");
        // Optionally, display this information in your UI or log it
        Log.d("Video Info", info.toString());

        builder.setMessage(info.toString());

        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // Helper method to get video codec (this would need to be implemented)
    @SuppressLint("DefaultLocale")
    private Map<String, String> getVideoDetails(String path) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Map<String, String> videoDetails = new HashMap<>();
        try {
            retriever.setDataSource(path); // Set the data source for the video

            // Extracting video codec
            String videoCodec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            videoDetails.put("Codec", videoCodec != null ? videoCodec.split("/")[1] : "Unknown");

            // Extracting video width and height
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            if (width != null && height != null) {
                int w = Integer.parseInt(width);
                int h = Integer.parseInt(height);
                videoDetails.put("Resolution", w + " x " + h);

                // Use the smaller dimension for resolution quality
                int resolutionHeight = Math.min(w, h);
                String quality = getQualityLabel(resolutionHeight);
                videoDetails.put("Quality", quality); // Store the resolution quality as "480p", "720p", etc.
            } else {
                videoDetails.put("Resolution", "Unknown");
                videoDetails.put("Quality", "Unknown Quality");
            }

            // Extracting bitrate
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null) {
                long bitrateBps = Long.parseLong(bitrate); // Assuming the bitrate is in bps
                double bitrateMbps = bitrateBps / 1_000_000.0; // Convert to Mbps
                videoDetails.put("Bitrate", String.format("%.2f Mbps", bitrateMbps)); // Store formatted bitrate in Mbps
            } else {
                videoDetails.put("Bitrate", "Unknown");
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            retriever.release(); // Release the retriever resources
        }
        return videoDetails; // Return the details map
    }

    // Method to determine the quality label based on the height
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

    // Existing formatDuration method from previous messages
    @SuppressLint("DefaultLocale")
    private String formatDuration(String duration) {
        if (duration == null) return "Unknown";
        long durationMs = Long.parseLong(duration);

        long hours = (durationMs / 1000) / 3600;
        long minutes = ((durationMs / 1000) % 3600) / 60;
        long seconds = (durationMs / 1000) % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    // Method to share video
    private void shareVideo(VideoFile videoFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(videoFile.getPath()));
        context.startActivity(Intent.createChooser(shareIntent, "Share video via"));
    }
}