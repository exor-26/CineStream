package com.example.cinestream;

import java.util.List;

public class VideoFile {
    private String id;
    private String name;
    private String path;
    private List<String> qualities; // List of available qualities (optional)

    // Constructor for use with qualities
    public VideoFile(String id, String name, String path, List<String> qualities) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.qualities = qualities; // Initialize qualities
    }

    // Constructor for use without qualities
    public VideoFile(String id, String name, String path) {
        this(id, name, path, null); // Calls the other constructor with qualities set to null
    }

    // Constructor for use with date modified (if required)
    public VideoFile(String id, String name, String path, long dateModified) {
        this(id, name, path, null); // Calls the constructor without qualities
        // Optionally, store dateModified if you need to keep track of it
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public List<String> getQualities() {
        return qualities; // Getter for qualities
    }

    // Setters
    public void setName(String name) {
        this.name = name;
    }

    public void setPath(String path) {
        this.path = path;
    }

}
