package com.cns.dropzone.model;

public class FileItem {
    private final String name;
    private final String size;
    private final String lastModified;
    private final String id;

    private boolean downloaded;

    public FileItem(String name, String size, String lastModified, String id, boolean downloaded) {
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
        this.id = id;
        this.downloaded = downloaded;
    }

    public String getName() { return name; }
    public String getSize() { return size; }
    public String getLastModified() { return lastModified; }
    public String getId() { return id; }
    public boolean isDownloaded() { return downloaded; }
    public void setDownloaded(boolean downloaded) { this.downloaded = downloaded; }
}