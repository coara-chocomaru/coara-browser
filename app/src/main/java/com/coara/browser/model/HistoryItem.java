package com.coara.browser.model;

public class HistoryItem {
    private final String title;
    private final String url;
    private final long timestamp;

    public HistoryItem(String title, String url) {
        this(title, url, System.currentTimeMillis());
    }

    public HistoryItem(String title, String url, long timestamp) {
        this.title = title;
        this.url = url;
        this.timestamp = timestamp;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
