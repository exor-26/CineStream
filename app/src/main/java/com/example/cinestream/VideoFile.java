package com.example.cinestream;

import android.net.Uri;

public class VideoFile {
    private final long id;
    private String name;
    private final Uri contentUri;
    private final long sizeBytes;
    private final long dateModified;
    private final long durationMs;
    private final String folderName;
    private final String folderKey;
    private final String playbackKey;

    public VideoFile(
            long id,
            String name,
            Uri contentUri,
            long sizeBytes,
            long dateModified,
            long durationMs,
            String folderName,
            String folderKey,
            String playbackKey
    ) {
        this.id = id;
        this.name = name;
        this.contentUri = contentUri;
        this.sizeBytes = sizeBytes;
        this.dateModified = dateModified;
        this.durationMs = durationMs;
        this.folderName = folderName;
        this.folderKey = folderKey;
        this.playbackKey = playbackKey;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Uri getContentUri() {
        return contentUri;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getDateModified() {
        return dateModified;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getFolderName() {
        return folderName;
    }

    public String getFolderKey() {
        return folderKey;
    }

    public String getPlaybackKey() {
        return playbackKey;
    }

    public void setName(String name) {
        this.name = name;
    }
}
