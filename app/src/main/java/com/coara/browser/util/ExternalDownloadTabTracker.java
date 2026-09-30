package com.coara.browser.util;

import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class ExternalDownloadTabTracker {
    private static final long MAX_STATE_AGE_MS = 10 * 60 * 1000L;
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

    public static boolean isExternal(WebView webView) {
        return webView != null && STATES.containsKey(webView);
    }

    public static void clear(WebView webView) {
        if (webView != null) {
            STATES.remove(webView);
        }
    }

    public static void markUserNavigation(WebView webView) {
        if (webView == null) {
            return;
        }
        State state = STATES.get(webView);
        if (state != null) {
            state.userNavigated = true;
            state.updatedAt = System.currentTimeMillis();
        }
    }

    public static void onMainFrameNavigation(WebView webView, boolean isMainFrame, boolean hasGesture) {
        onMainFrameNavigation(webView, isMainFrame, hasGesture, false, null);
    }

    public static void onMainFrameNavigation(WebView webView, boolean isMainFrame, boolean hasGesture,
                                             boolean isRedirect, String url) {
        if (webView == null || !isMainFrame) {
            return;
        }
        State state = STATES.get(webView);
        if (state == null) {
            return;
        }
        if (hasGesture) {
            state.userNavigated = true;
        }
        String normalized = normalize(url);
        if (!normalized.isEmpty()) {
            String previous = state.lastMainFrameUrl;
            if (previous != null && !sameUrl(previous, normalized)) {
                if (isRedirect) {
                    state.automaticNavigationCount++;
                    state.lastWasRedirect = true;
                } else if (!state.userNavigated) {
                    state.automaticNavigationCount++;
                    state.lastWasRedirect = false;
                }
            }
            state.lastMainFrameUrl = normalized;
            if (!sameUrl(normalized, state.initialUrl) && looksLikeIntermediaryDownloadPage(normalized)) {
                state.intermediarySeen = true;
            }
        }
        state.updatedAt = System.currentTimeMillis();
    }

    public static void onPageStarted(WebView webView, String url) {
        updatePage(webView, url, false);
    }

    public static void onPageFinished(WebView webView, String url) {
        updatePage(webView, url, true);
    }

    public static boolean shouldCloseAfterDownload(WebView webView, String downloadUrl) {
        if (webView == null) {
            return false;
        }
        State state = STATES.get(webView);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - state.createdAt > MAX_STATE_AGE_MS || state.userNavigated) {
            return false;
        }
        String currentUrl = safeWebViewUrl(webView);
        String normalizedDownloadUrl = normalize(downloadUrl);
        if (currentUrl.isEmpty() || normalizedDownloadUrl.isEmpty()) {
            return false;
        }
        if (looksLikeFileUrl(normalizedDownloadUrl)) {
            if (sameUrl(currentUrl, normalizedDownloadUrl)) {
                return false;
            }
        }
        if (sameUrl(currentUrl, state.initialUrl)) {
            return false;
        }
        if (sameUrl(currentUrl, normalizedDownloadUrl)) {
            return false;
        }
        if (!state.intermediarySeen) {
            return false;
        }
        if (state.automaticNavigationCount <= 0) {
            return false;
        }
        if (looksLikeDirectHost(currentUrl) && !looksLikeIntermediaryDownloadPage(currentUrl)) {
            return false;
        }
        if (looksLikeDirectDownloadHost(normalizedDownloadUrl)) {
            return true;
        }
        return looksLikeIntermediaryDownloadPage(currentUrl) && !sameUrl(currentUrl, normalizedDownloadUrl);
    }

    private static void updatePage(WebView webView, String url, boolean finished) {
        if (webView == null) {
            return;
        }
        State state = STATES.get(webView);
        if (state == null) {
            return;
        }
        String normalized = normalize(url);
        if (!normalized.isEmpty()) {
            String previous = state.lastPageUrl;
            if (previous != null && !sameUrl(previous, normalized) && !state.userNavigated) {
                state.automaticNavigationCount++;
            }
            state.lastPageUrl = normalized;
            state.lastFinished = finished;
            if (!sameUrl(normalized, state.initialUrl) && looksLikeIntermediaryDownloadPage(normalized)) {
                state.intermediarySeen = true;
            }
        }
        state.updatedAt = System.currentTimeMillis();
    }

    private static String safeWebViewUrl(WebView webView) {
        try {
            return normalize(webView.getUrl());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean looksLikeFileUrl(String url) {
        String normalized = normalize(url).toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\.(apk|xapk|apks|zip|7z|rar|gz|bz2|tar|pdf|docx?|xlsx?|pptx?|jpe?g|png|gif|webp|mp3|m4a|mp4|webm|bin)(?:$|[?#]).*")
                || normalized.contains("/releases/download/")
                || normalized.contains("/download/files/")
                || normalized.contains("/assets/") && normalized.contains("?download");
    }

    private static boolean looksLikeDirectDownloadHost(String url) {
        String value = normalize(url).toLowerCase(Locale.ROOT);
        return value.contains("github.com/") && value.contains("/releases/download/")
                || value.contains("objects.githubusercontent.com/")
                || value.contains("github-production-release-asset")
                || value.contains("release-assets.githubusercontent.com")
                || value.contains("raw.githubusercontent.com/")
                || value.contains("storage.googleapis.com/")
                || value.contains("download-cdn")
                || value.contains("cdn.");
    }

    private static boolean looksLikeDirectHost(String url) {
        String value = normalize(url).toLowerCase(Locale.ROOT);
        return value.contains("github.com/") || value.contains("gitlab.com/") || value.contains("bitbucket.org/");
    }

    private static boolean looksLikeIntermediaryDownloadPage(String url) {
        String value = normalize(url).toLowerCase(Locale.ROOT);
        try {
            Uri uri = Uri.parse(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            String combined = path + "?" + query;
            if (combined.matches(".*(?:^|[/_?&=-])(download|downloads|dl|downloadpage|download-file|downloadfile|redirect-download|file-download)(?:[/._?&=-]|$).*")) {
                if (looksLikeFileUrl(value)) {
                    return false;
                }
                return true;
            }
            if (query.matches(".*(?:^|&)(?:download|dl|action)=?(?:1|true|download)?(?:&|$).*)")) {
                return true;
            }
            String last = uri.getLastPathSegment();
            if (!TextUtils.isEmpty(last) && !last.contains(".")) {
                return query.contains("download") || query.contains("file") || path.contains("/download/");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean sameAuthority(String first, String second) {
        try {
            Uri a = Uri.parse(first);
            Uri b = Uri.parse(second);
            String ah = a.getHost();
            String bh = b.getHost();
            int ap = a.getPort() == -1 ? defaultPort(a.getScheme()) : a.getPort();
            int bp = b.getPort() == -1 ? defaultPort(b.getScheme()) : b.getPort();
            return ah != null && bh != null && ah.equalsIgnoreCase(bh) && ap == bp
                    && safe(a.getScheme()).equalsIgnoreCase(safe(b.getScheme()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) return 443;
        if ("http".equalsIgnoreCase(scheme)) return 80;
        return -1;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
            return uri.buildUpon().fragment(null).build().toString();
        } catch (Exception e) {
            return value;
        }
    }

    private static final class State {
        final String initialUrl;
        final long createdAt = System.currentTimeMillis();
        long updatedAt = createdAt;
        String lastMainFrameUrl;
        String lastPageUrl;
        boolean lastFinished;
        boolean userNavigated;
        boolean intermediarySeen;
        boolean lastWasRedirect;
        int automaticNavigationCount;

        State(String initialUrl) {
            this.initialUrl = initialUrl;
            this.lastMainFrameUrl = initialUrl;
            this.lastPageUrl = initialUrl;
        }
    }
}
