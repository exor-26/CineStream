package com.example.cinestream;

public class FolderItem {
    private final String name;
    private final String path;
    private final int videoCount;

    public FolderItem(String name, String path, int videoCount) {
        this.name       = name;
        this.path       = path;
        this.videoCount = videoCount;
    }

    public String getName()     { return name; }
    public String getPath()     { return path; }
    public int getVideoCount()  { return videoCount; }
}