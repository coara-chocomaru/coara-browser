package com.coara.browser.util;

import android.net.Uri;
import android.webkit.WebView;

import java.util.Locale;
import java.util.regex.Pattern;


public final class SwipeRefreshPolicy {
    private SwipeRefreshPolicy() {}

    private static final Pattern BLOCKED_HOSTS = Pattern.compile(
            "(^|\\.)(chatgpt\\.com|openai\\.com"
            + "|youtube\\.com|youtu\\.be"
            + "|x\\.com|twitter\\.com"
            + "|instagram\\.com|tiktok\\.com|facebook\\.com|threads\\.net"
            + "|reddit\\.com|news\\.ycombinator\\.com"
            + "|mail\\.google\\.com|drive\\.google\\.com|docs\\.google\\.com"
            + "|calendar\\.google\\.com|keep\\.google\\.com|meet\\.google\\.com"
            + "|github\\.com|gitlab\\.com|bitbucket\\.org"
            + "|notion\\.so|notion\\.site"
            + "|slack\\.com|discord\\.com|discord\\.gg"
            + "|figma\\.com|miro\\.com"
            + "|trello\\.com|linear\\.app|asana\\.com"
            + "|spotify\\.com|netflix\\.com|primevideo\\.com|hulu\\.com"
            + "|zenn\\.dev|qiita\\.com|medium\\.com|dev\\.to"
            + "|maps\\.google\\.[a-z.]+"     
            + "|amazon\\.co\\.jp|amazon\\.com"
            + "|mercari\\.com|rakuten\\.co\\.jp"
            + "|line\\.me|liff\\.line\\.me"
            + "|nicovideo\\.jp|niconico\\.jp"
            + ")$",
            Pattern.CASE_INSENSITIVE
    );


    private static final Pattern BLOCKED_PATHS = Pattern.compile(
            "(/watch($|[/?#])"
            + "|/shorts($|[/?#])"
            + "|/live($|[/?#])"
            + "|/reels($|[/?#])"
            + "|/status/"
            + "|/messages($|[/?#])"
            + "|/chat($|[/?#])"
            + "|/inbox($|[/?#])"
            + "|/feed($|[/?#])"
            + "|/timeline($|[/?#])"
            + "|/notifications($|[/?#])"
            + "|/explore($|[/?#])"
            + "|/discover($|[/?#])"
            + "|/maps($|[/?#])"
            + "|/maps/place"
            + "|/maps/search"
            + "|/maps/dir"
            + ")",
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
            "^/(search|imgres|searchbyimage|lens|images/search"
            + "|discover|explore|feed|timeline|posts|maps)(?:$|[/?#])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HASH_ROUTING = Pattern.compile(
            "#(/|!/|!\\w|[a-zA-Z0-9_-]{2,}/)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DEEP_SPA_PATH = Pattern.compile(
            "^(/[^/.?#]+){3,}$"
    );

    private static final Pattern FILE_EXT_AT_END = Pattern.compile(
            "/[^/?#]+\\.[a-zA-Z0-9]{2,5}(\\?[^#]*)?$"
    );

    private static final Pattern SPA_QUERY_PARAMS = Pattern.compile(
            "(^|&)(page|view|tab|section|step|panel|mode|screen|route)=",
            Pattern.CASE_INSENSITIVE
    );


    public static boolean shouldEnablePullToRefresh(WebView webView, String url) {
        if (webView == null) return false;
        if (url == null || url.isEmpty()) return true;

        String lower = url.toLowerCase(Locale.ROOT);

         if (SpaStateManager.getInstance().isSpa(webView)) return false;

        if (lower.startsWith("about:")
                || lower.startsWith("javascript:")
                || lower.startsWith("file:")
                || lower.startsWith("content:")
                || lower.startsWith("data:")) {
            return false;
        }

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return true;
        }

        String host     = uri.getHost();
        String path     = uri.getPath();
        String query    = uri.getQuery();
        String fragment = uri.getFragment();
        String normPath  = path  != null ? path.toLowerCase(Locale.ROOT)  : "";
        String normQuery = query != null ? query.toLowerCase(Locale.ROOT) : "";

        if (host != null) {
            String normHost = host.toLowerCase(Locale.ROOT);
            if (BLOCKED_HOSTS.matcher(normHost).find()) return false;
            if (isSearchOrMapPage(normHost, normPath, normQuery)) return false;
        }

        if (BLOCKED_PATHS.matcher(normPath).find()) return false;

        if (fragment != null && HASH_ROUTING.matcher(fragment).find()) return false;
        if (HASH_ROUTING.matcher(url).find()) return false;

        if (!normPath.isEmpty()) {
            if (!FILE_EXT_AT_END.matcher(normPath).find()
                    && DEEP_SPA_PATH.matcher(normPath).matches()) {
                return false;
            }
        }

        if (!normQuery.isEmpty() && SPA_QUERY_PARAMS.matcher(normQuery).find()) return false;

        return true;
    }


    public static boolean shouldBlockPullToRefresh(WebView webView) {
        if (webView == null) return true;
        String url = webView.getUrl();
        if (!shouldEnablePullToRefresh(webView, url)) return true;
    
        return webView.canScrollVertically(-1);
    }


    private static boolean isSearchOrMapPage(String host, String path, String query) {
        if (host == null) return false;

        if (GOOGLE_IMAGE_HOST.matcher(host).find()) return true;

        if (GOOGLE_HOST.matcher(host).find()) {
            if (path != null && (path.startsWith("/maps") || path.contains("/maps/"))) {
                return true;
            }
            if (path != null && SEARCH_LIKE_PATH.matcher(path).find()) return true;
            if (query != null && (query.contains("tbm=isch")
                    || query.contains("tbm=shop")
                    || query.contains("tbm=vid")
                    || query.contains("tbm=nws")
                    || query.contains("udm=2")
                    || query.contains("udm=14"))) {
                return true;
            }
            if (path != null && path.contains("/search")) return true;
        }

        if (host.endsWith("bing.com")) {
            if (path != null) {
                String lp = path.toLowerCase(Locale.ROOT);
                if (lp.startsWith("/images/search") || lp.startsWith("/search")) return true;
  
                if (lp.startsWith("/maps")) return true;
            }
        }

        if (host.endsWith("duckduckgo.com")) {
            if (query != null && query.contains("ia=images")) return true;
            if (path != null && path.toLowerCase(Locale.ROOT).startsWith("/?")) return true;
        }

        if (host.endsWith("openstreetmap.org")
                || host.contains("mapbox.com")
                || host.contains("leaflet")
                || host.endsWith("yahooapis.jp")
                || host.endsWith("map.yahoo.co.jp")) {
            return true;
        }

        if (host.contains("sai") || host.contains("f5.si")) {
            if (path != null && SEARCH_LIKE_PATH.matcher(path).find()) return true;
            if (query != null && (query.contains("search=")
                    || query.contains("q=")
                    || query.contains("keyword="))) {
                return true;
            }
        }

        return false;
    }
}
