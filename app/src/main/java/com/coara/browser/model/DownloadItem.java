package com.coara.browser.model;

public class DownloadItem {
    public long downloadId;
    public String title;
    public String description;
    public int status;
    public long downloadedSize;
    public long totalSize;
    public String localUri;
    public String downloadUrl;
    public boolean isPaused;
    public String filePath;

    public DownloadItem(long downloadId, String title, String description, int status, long downloadedSize, long totalSize, String localUri, String downloadUrl) {
        this.downloadId = downloadId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.downloadedSize = downloadedSize;
        this.totalSize = totalSize;
        this.localUri = localUri;
        this.downloadUrl = downloadUrl;
        this.isPaused = false;
        this.filePath = "";
    }

    public int getProgress() {
        return totalSize > 0 ? (int) ((downloadedSize * 100) / totalSize) : 0;
    }
}
