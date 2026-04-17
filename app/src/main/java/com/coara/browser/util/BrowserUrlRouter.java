package com.coara.browser.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.Locale;

public final class BrowserUrlRouter {
    private BrowserUrlRouter() {}

    public static String normalizeUserInput(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (looksLikeUrlWithScheme(trimmed)) {
            return trimmed;
        }
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            boolean hostLike = true;
            for (int i = 0; i < colon; i++) {
                char c = trimmed.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) {
                    hostLike = false;
                    break;
                }
            }
            if (hostLike) {
                int i = colon + 1;
                boolean hasDigit = false;
                while (i < trimmed.length()) {
                    char c = trimmed.charAt(i);
                    if (!Character.isDigit(c)) break;
                    hasDigit = true;
                    i++;
                }
                if (hasDigit && (i == trimmed.length() || trimmed.charAt(i) == '/')) {
                    return "http://" + trimmed;
                }
            }
        }
        if (URLUtil.isValidUrl(trimmed)) {
            return trimmed;
        }
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            return "https://www.google.com/search?q=" + Uri.encode(trimmed);
        }
        return "http://" + trimmed;
    }

    public static boolean isWebUrl(String url) {
        String scheme = getScheme(url);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    public static boolean handleUrlLoading(Activity activity, WebView webView, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            return true;
        }

        String scheme = getScheme(url);
        if (scheme == null) {
            return false;
        }

        if (isWebScheme(scheme) || isInternalWebScheme(scheme)) {
            return false;
        }

        if ("intent".equals(scheme)) {
            return handleIntentScheme(activity, webView, url);
        }

        if ("android-app".equals(scheme)) {
            return handleAndroidAppScheme(activity, webView, url);
        }

        Intent intent = buildIntentForScheme(url, scheme);
        if (intent == null) {
            return false;
        }

        return launchIntent(activity, webView, intent, null);
    }

    public static boolean handleIncomingViewIntent(Activity activity, WebView webView, Uri data) {
        if (data == null) {
            return false;
        }
        String url = data.toString();
        if (isWebUrl(url)) {
            return false;
        }
        return handleUrlLoading(activity, webView, url);
    }

    private static boolean handleIntentScheme(Activity activity, WebView webView, String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            if (intent == null) {
                return true;
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            String fallbackUrl = intent.getStringExtra("browser_fallback_url");
            return launchIntent(activity, webView, intent, fallbackUrl);
        } catch (Exception e) {
            Toast.makeText(activity, "URLを開けませんでした", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private static boolean handleAndroidAppScheme(Activity activity, WebView webView, String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_ANDROID_APP_SCHEME);
            if (intent == null) {
                return true;
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            return launchIntent(activity, webView, intent, null);
        } catch (Exception e) {
            Toast.makeText(activity, "URLを開けませんでした", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private static boolean launchIntent(Activity activity, WebView webView, Intent intent, String fallbackUrl) {
        try {
            if (intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(intent);
                return true;
            }
            if (!TextUtils.isEmpty(fallbackUrl)) {
                return loadFallback(activity, webView, fallbackUrl);
            }
            Uri data = intent.getData();
            if (data != null) {
                String dataString = data.toString();
                if (isWebUrl(dataString)) {
                    return loadFallback(activity, webView, dataString);
                }
            }
        } catch (ActivityNotFoundException ignored) {
            if (!TextUtils.isEmpty(fallbackUrl)) {
                return loadFallback(activity, webView, fallbackUrl);
            }
        } catch (Exception ignored) {
            if (!TextUtils.isEmpty(fallbackUrl)) {
                return loadFallback(activity, webView, fallbackUrl);
            }
        }
        Toast.makeText(activity, "対応アプリが見つかりませんでした", Toast.LENGTH_SHORT).show();
        return true;
    }

    private static boolean loadFallback(Activity activity, WebView webView, String fallbackUrl) {
        String normalized = fallbackUrl.trim();
        if (normalized.isEmpty()) {
            Toast.makeText(activity, "URLを開けませんでした", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (webView != null) {
            webView.post(() -> webView.loadUrl(normalized));
        } else {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(normalized)));
        }
        return true;
    }

    private static Intent buildIntentForScheme(String url, String scheme) {
        Uri uri = Uri.parse(url);
        Intent intent;
        switch (scheme) {
            case "tel":
            case "telprompt":
                intent = new Intent(Intent.ACTION_DIAL, uri);
                break;
            case "sms":
            case "smsto":
            case "mms":
            case "mmsto":
            case "mailto":
                intent = new Intent(Intent.ACTION_SENDTO, uri);
                break;
            case "geo":
            case "market":
            case "maps":
                intent = new Intent(Intent.ACTION_VIEW, uri);
                break;
            default:
                intent = new Intent(Intent.ACTION_VIEW, uri);
                break;
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        return intent;
    }

    private static boolean isWebScheme(String scheme) {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private static boolean isInternalWebScheme(String scheme) {
        return "file".equals(scheme)
                || "content".equals(scheme)
                || "about".equals(scheme)
                || "data".equals(scheme)
                || "blob".equals(scheme)
                || "javascript".equals(scheme)
                || "filesystem".equals(scheme);
    }

    private static boolean looksLikeUrlWithScheme(String input) {
        if (TextUtils.isEmpty(input)) {
            return false;
        }
        if (input.contains("://")) {
            return true;
        }
        String scheme = getScheme(input);
        if (scheme == null) {
            return false;
        }
        switch (scheme) {
            case "about":
            case "blob":
            case "content":
            case "data":
            case "file":
            case "ftp":
            case "intent":
            case "javascript":
            case "mailto":
            case "market":
            case "maps":
            case "geo":
            case "android-app":
            case "sms":
            case "smsto":
            case "mms":
            case "mmsto":
            case "tel":
            case "telprompt":
            case "webcal":
            case "whatsapp":
            case "line":
            case "tg":
            case "weixin":
            case "fb":
            case "twitter":
            case "x":
            case "skype":
            case "slack":
            case "discord":
            case "instagram":
            case "snapchat":
            case "reddit":
            case "spotify":
            case "zoomus":
            case "viber":
                return true;
            default:
                return false;
        }
    }

    private static String getScheme(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            return scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
