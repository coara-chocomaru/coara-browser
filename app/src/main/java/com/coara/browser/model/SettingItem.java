package com.coara.browser.model;

public class SettingItem {
    private final String title;
    private final String summary;
    private final Runnable action;

    public SettingItem(String title, String summary, Runnable action) {
        this.title = title;
        this.summary = summary;
        this.action = action;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public Runnable getAction() {
        return action;
    }
}
