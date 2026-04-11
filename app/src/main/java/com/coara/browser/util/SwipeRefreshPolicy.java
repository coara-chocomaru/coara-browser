package com.coara.browser.util;

import android.net.Uri;
import android.webkit.WebView;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SwipeRefreshPolicy {
    private SwipeRefreshPolicy() {}

    private static final Pattern BLOCKED_HOSTS = Pattern.compile(
            "(^|\\.)(chatgpt\\.com|openai\\.com|youtube\\.com|youtu\\.be|x\\.com|twitter\\.com|instagram\\.com|tiktok\\.com|facebook\\.com|reddit\\.com|news\\.ycombinator\\.com|mail\\.google\\.com|drive\\.google\\.com|docs\\.google\\.com|calendar\\.google\\.com|keep\\.google\\.com)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BLOCKED_PATHS = Pattern.compile(
            "(/watch($|[/?#])|/shorts($|[/?#])|/live($|[/?#])|/reels($|[/?#])|/status/|/messages($|[/?#])|/chat($|[/?#])|/inbox($|[/?#]))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GOOGLE_HOST = Pattern.compile(
            "(^|\\.)(google\\.[a-z.]+|googleusercontent\\.com)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GOOGLE_IMAGE_HOST = Pattern.compile(
            "(^|\\.)(images\\.google\\.[a-z.]+|lens\\.google\\.[a-z.]+)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SEARCH_LIKE_PATH = Pattern.compile(
            "^/(search|imgres|searchbyimage|lens|images/search|discover|explore|feed|timeline|posts)(?:$|[/?#])",
            Pattern.CASE_INSENSITIVE
    );

    public static boolean shouldEnablePullToRefresh(WebView webView, String url) {
        if (webView == null) {
            return false;
        }
        if (url == null || url.isEmpty()) {
            return true;
        }

        String normalized = url.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("about:")
                || normalized.startsWith("javascript:")
                || normalized.startsWith("file:")
                || normalized.startsWith("content:")
                || normalized.startsWith("data:")) {
            return false;
        }

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return true;
        }

        String host = uri.getHost();
        String path = uri.getPath();
        String query = uri.getQuery();
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);

        if (host != null) {
            String normalizedHost = host.toLowerCase(Locale.ROOT);

            if (BLOCKED_HOSTS.matcher(normalizedHost).find()) {
                return false;
            }

            if (isSearchOrImageResultsPage(normalizedHost, normalizedPath, normalizedQuery)) {
                return false;
            }

            if (BLOCKED_PATHS.matcher(normalizedPath).find()) {
                return false;
            }
        } else if (SEARCH_LIKE_PATH.matcher(normalizedPath).find()) {
            return false;
        }

        return true;
    }

    public static boolean shouldBlockPullToRefresh(WebView webView) {
        if (webView == null) {
            return true;
        }
        String url = webView.getUrl();
        if (!shouldEnablePullToRefresh(webView, url)) {
            return true;
        }

        // Chrome-like behavior: only allow refresh when the content is actually at the top.
        return webView.canScrollVertically(-1);
    }

    private static boolean isSearchOrImageResultsPage(String host, String path, String query) {
        if (host == null) {
            return false;
        }

        if (GOOGLE_IMAGE_HOST.matcher(host).find()) {
            return true;
        }

        if (GOOGLE_HOST.matcher(host).find()) {
            if (path != null && SEARCH_LIKE_PATH.matcher(path).find()) {
                return true;
            }
            if (query != null && (query.contains("tbm=isch")
                    || query.contains("tbm=shop")
                    || query.contains("tbm=vid")
                    || query.contains("tbm=nws")
                    || query.contains("udm=2")
                    || query.contains("udm=14"))) {
                return true;
            }
            if (path != null && path.contains("/search")) {
                return true;
            }
        }

        if (host.endsWith("bing.com")) {
            if (path != null && path.toLowerCase(Locale.ROOT).startsWith("/images/search")) {
                return true;
            }
            if (path != null && path.toLowerCase(Locale.ROOT).startsWith("/search")) {
                return true;
            }
        }

        if (host.endsWith("duckduckgo.com")) {
            if (query != null && query.contains("ia=images")) {
                return true;
            }
            if (path != null && path.toLowerCase(Locale.ROOT).startsWith("/?")) {
                return true;
            }
        }

        if (host.contains("sai") || host.contains("f5.si")) {
            if (path != null && SEARCH_LIKE_PATH.matcher(path).find()) {
                return true;
            }
            if (query != null && (query.contains("search=") || query.contains("q=") || query.contains("keyword="))) {
                return true;
            }
        }

        return false;
    }
}
