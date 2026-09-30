package com.coara.browser.util;

import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ExternalDownloadTabTracker {
    private static final Map<WebView, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ExternalDownloadTabTracker() {
    }

    public static void markExternal(WebView webView, String initialUrl) {
        if (webView == null) {
            return;
        }
        String normalized = normalize(initialUrl);
        if (normalized.isEmpty()) {
            clear(webView);
            return;
        }
        STATES.put(webView, new State(normalized));
    }

    public static void clear(WebView webView) {
        if (webView != null) {
            STATES.remove(webView);
        }
    }

    public static void onMainFrameNavigation(WebView webView, boolean isMainFrame, boolean hasGesture) {
        if (webView == null || !isMainFrame || !hasGesture) {
            return;
        }
        State state = STATES.get(webView);
        if (state != null) {
            state.userNavigated = true;
        }
    }

    public static boolean shouldCloseAfterDownload(WebView webView, String downloadUrl) {
        if (webView == null) {
            return false;
        }
        State state = STATES.get(webView);
        if (state == null || state.userNavigated) {
            return false;
        }
        String currentUrl;
        try {
            currentUrl = normalize(webView.getUrl());
        } catch (Exception e) {
            return false;
        }
        String normalizedDownloadUrl = normalize(downloadUrl);
        if (currentUrl.isEmpty() || state.initialUrl.isEmpty()) {
            return false;
        }
        if (sameUrl(currentUrl, state.initialUrl)) {
            return false;
        }
        if (!normalizedDownloadUrl.isEmpty() && sameUrl(currentUrl, normalizedDownloadUrl)) {
            return false;
        }
        return true;
    }

    private static boolean sameUrl(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    private static String normalize(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(value);
            Uri normalized = uri.buildUpon().fragment(null).build();
            return normalized.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static final class State {
        final String initialUrl;
        boolean userNavigated;

        State(String initialUrl) {
            this.initialUrl = initialUrl;
        }
    }
}
