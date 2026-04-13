package com.coara.browser.util;

import android.net.Uri;
import android.webkit.WebSettings;
import java.util.Locale;
import java.util.regex.Pattern;


public final class CacheModePolicy {
    private CacheModePolicy() {}

    private static final Pattern NO_CACHE_PATH = Pattern.compile(
            "/(login|logout|signin|signout|sign-in|sign-out"
            + "|auth|oauth|sso|saml|callback|authorize"
            + "|session|account/security"
            + "|checkout|payment|pay|cart/confirm|order/confirm"
            + "|password/reset|password/change|verify|2fa|mfa"
            + ")($|[/?#])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NO_CACHE_QUERY = Pattern.compile(
            "(^|&)(token|access_token|id_token|code|state|nonce|oauth_token"
            + "|session_id|csrftoken)=",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NO_CACHE_HOSTS = Pattern.compile(
            "(^|\\.)(chatgpt\\.com|chat\\.openai\\.com"
            + "|mail\\.google\\.com|inbox\\.google\\.com"
            + "|discord\\.com"
            + "|slack\\.com"
            + "|chat\\.line\\.me|liff\\.line\\.me"
            + ")$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DEFAULT_CACHE_HOSTS = Pattern.compile(
            "(^|\\.)(google\\.[a-z.]+"
            + "|googleapis\\.com|gstatic\\.com"
            + "|youtube\\.com|youtu\\.be"
            + "|twitter\\.com|x\\.com"
            + "|instagram\\.com|facebook\\.com|threads\\.net|tiktok\\.com"
            + "|reddit\\.com|news\\.ycombinator\\.com"
            + "|github\\.com|gitlab\\.com|bitbucket\\.org"
            + "|notion\\.so|notion\\.site"
            + "|figma\\.com|miro\\.com"
            + "|spotify\\.com|netflix\\.com|primevideo\\.com"
            + "|nicovideo\\.jp|niconico\\.jp"
            + "|amazon\\.co\\.jp|amazon\\.com"
            + "|mercari\\.com|rakuten\\.co\\.jp"
            + "|line\\.me"
            + "|zenn\\.dev|qiita\\.com|medium\\.com|dev\\.to"
            + ")$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DEFAULT_CACHE_PATH = Pattern.compile(
            "^/(api|graphql|v1|v2|v3|rpc|ws|socket\\.io|realtime|stream|feed|timeline)($|/)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LEGACY_NO_CACHE = Pattern.compile(
            "(^|[/.])(chatx2|chatx|chat|auth|nicovideo|login|disk|cgi|session|cloud)($|[/.])",
            Pattern.CASE_INSENSITIVE
    );


    public static int determineForUrl(String url) {
        if (url == null || url.isEmpty()) return WebSettings.LOAD_DEFAULT;

        String lower = url.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return WebSettings.LOAD_DEFAULT;
        }

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return WebSettings.LOAD_DEFAULT;
        }

        String host  = uri.getHost();
        String path  = uri.getPath()  != null ? uri.getPath().toLowerCase(Locale.ROOT)  : "";
        String query = uri.getQuery() != null ? uri.getQuery().toLowerCase(Locale.ROOT) : "";

          if (LEGACY_NO_CACHE.matcher(lower).find()) {
            return WebSettings.LOAD_NO_CACHE;
        }

        if (!path.isEmpty() && NO_CACHE_PATH.matcher(path).find()) {
            return WebSettings.LOAD_NO_CACHE;
        }

        if (!query.isEmpty() && NO_CACHE_QUERY.matcher(query).find()) {
            return WebSettings.LOAD_NO_CACHE;
        }

        if (host != null) {
            String normHost = host.toLowerCase(Locale.ROOT);

            if (NO_CACHE_HOSTS.matcher(normHost).find()) {
                return WebSettings.LOAD_NO_CACHE;
            }

        
           if (normHost.contains("google.") && path.startsWith("/maps")) {
                return WebSettings.LOAD_DEFAULT;
            }

            if (DEFAULT_CACHE_HOSTS.matcher(normHost).find()) {
                return WebSettings.LOAD_DEFAULT;
            }
        }

        if (!path.isEmpty() && DEFAULT_CACHE_PATH.matcher(path).find()) {
            return WebSettings.LOAD_DEFAULT;
        }

        return WebSettings.LOAD_CACHE_ELSE_NETWORK;
    }


    public static int determinePostLoad(String url, boolean isSpa) {
        int base = determineForUrl(url);
        if (base == WebSettings.LOAD_NO_CACHE) return WebSettings.LOAD_NO_CACHE;
        if (isSpa) return WebSettings.LOAD_DEFAULT;
        return base;
    }
}
