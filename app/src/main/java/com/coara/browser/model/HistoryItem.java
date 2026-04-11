package com.coara.browser.model;

public class HistoryItem {
    private final String title;
    private final String url;

    public HistoryItem(String title, String url) {
        this.title = title;
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }
}
