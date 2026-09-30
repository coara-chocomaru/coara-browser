package com.coara.browser.util;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import com.coara.browser.DownloadHistoryManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadFallbackManager {
    public static final class Spec {
        final Activity activity;
        final WebView webView;
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final long contentLength;
        final String referer;
        final String initialFileName;
        final String effectiveMimeType;
        final boolean basicAuthEnabled;

        public Spec(Activity activity, WebView webView, String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength,
                    String referer, String initialFileName, String effectiveMimeType,
                    boolean basicAuthEnabled) {
            this.activity = activity;
            this.webView = webView;
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.referer = referer;
            this.initialFileName = initialFileName;
            this.effectiveMimeType = effectiveMimeType;
            this.basicAuthEnabled = basicAuthEnabled;
        }
    }

    private static final ConcurrentHashMap<Long, Spec> SPECS = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "CoaraDownloadFallback");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_REDIRECTS = 12;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 65536;
    private static final long PROGRESS_UPDATE_MS = 500;

    private DownloadFallbackManager() {
    }

    public static void register(long downloadId, Spec spec) {
        if (downloadId > 0 && spec != null) {
            SPECS.put(downloadId, spec);
        }
    }

    public static long startImmediateFallback(Activity activity, WebView webView, String url, String userAgent,
                                              String contentDisposition, String mimeType, long contentLength,
                                              String referer, boolean basicAuthEnabled, String description) {
        if (activity == null || DownloadSupport.isBlank(url) || !BrowserUrlRouter.isWebUrl(url) || isActivityUnavailable(activity)) {
            return -1L;
        }
        try {
            String effectiveMime = DownloadSupport.normalizeMimeType(mimeType, url);
            String fileName = DownloadSupport.resolveFileName(url, contentDisposition, effectiveMime);
            fileName = DownloadSupport.resolveUniqueDownloadFileName(activity, fileName);
            long id = DownloadHistoryManager.nextManualDownloadId();
            String filePath = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    fileName).getAbsolutePath();
            DownloadHistoryManager.addDownloadHistory(activity, id, fileName, filePath, userAgent, referer, basicAuthEnabled);
            DownloadHistoryManager.updateManualDownload(activity, id, fileName, filePath,
                    DownloadManager.STATUS_RUNNING, 0L, contentLength);
            Spec spec = new Spec(activity, webView, url, userAgent, contentDisposition, mimeType, contentLength,
                    referer, fileName, effectiveMime, basicAuthEnabled);
            EXECUTOR.execute(() -> runFallback(spec, id));
            return id;
        } catch (Exception e) {
            return -1L;
        }
    }

    public static void clear(long downloadId) {
        SPECS.remove(downloadId);
    }

    public static boolean shouldFallback(int reason) {
        return reason == DownloadManager.ERROR_UNKNOWN
                || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE
                || reason == DownloadManager.ERROR_HTTP_DATA_ERROR
                || reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS
                || reason == DownloadManager.ERROR_CANNOT_RESUME
                || reason == DownloadManager.ERROR_FILE_ERROR
                || reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS;
    }

    public static void onDownloadManagerFailure(Context context, DownloadManager dm, long downloadId, int reason) {
        Spec spec = SPECS.remove(downloadId);
        if (spec == null || !shouldFallback(reason)) {
            return;
        }
        removeDownload(dm, downloadId);
        EXECUTOR.execute(() -> runFallback(spec, downloadId));
    }

    public static void onPendingTimeout(Context context, DownloadManager dm, long downloadId) {
        Spec spec = SPECS.remove(downloadId);
        if (spec == null) {
            return;
        }
        removeDownload(dm, downloadId);
        EXECUTOR.execute(() -> runFallback(spec, downloadId));
    }

    public static void onSuspiciousSuccessfulDownload(Context context, DownloadManager dm, long downloadId, String localUri) {
        Spec spec = SPECS.remove(downloadId);
        if (spec == null) {
            return;
        }
        DownloadSupport.deleteLocalUri(context, localUri);
        removeDownload(dm, downloadId);
        EXECUTOR.execute(() -> runFallback(spec, downloadId));
    }

    private static void removeDownload(DownloadManager dm, long downloadId) {
        if (dm != null) {
            try {
                dm.remove(downloadId);
            } catch (Exception ignored) {
            }
        }
    }

    private static void runFallback(Spec spec, long downloadId) {
        if (spec.activity == null) {
            return;
        }
        if (isActivityUnavailable(spec.activity)) {
            DownloadHistoryManager.updateManualDownload(spec.activity, downloadId,
                    spec.initialFileName, "", DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
            return;
        }
        FallbackResult first = perform(spec, spec.url, null, null, null, null, 0, downloadId);
        if (first.needsCredentials && spec.basicAuthEnabled) {
            final String realm = first.realm == null ? "" : first.realm;
            final String authHost = hostOf(first.authUrl == null ? spec.url : first.authUrl);
            BasicAuthManager.markAuthenticationFailure(spec.activity, spec.webView, authHost, realm);
            UiThread.post(() -> BasicAuthManager.requestCredentials(
                    spec.activity, spec.webView, authHost, realm,
                    (username, password) -> {
                        if (username == null || password == null) {
                            DownloadHistoryManager.updateManualDownload(spec.activity, downloadId, spec.initialFileName, "",
                                    DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
                            toast(spec.activity, "Basic認証をキャンセルしました");
                            return;
                        }
                        EXECUTOR.execute(() -> {
                            FallbackResult second = perform(spec, spec.url, username, password, authHost, null, 0, downloadId);
                            if (!second.success) {
                                DownloadHistoryManager.updateManualDownload(spec.activity, downloadId, spec.initialFileName, "",
                                        DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
                                toast(spec.activity, second.needsCredentials ? "Basic認証に失敗しました"
                                        : (second.message == null ? "ダウンロードに失敗しました" : second.message));
                            }
                        });
                    }));
            return;
        }
        if (!first.success) {
            DownloadHistoryManager.updateManualDownload(spec.activity, downloadId, spec.initialFileName, "",
                    DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
            toast(spec.activity, first.message == null ? "ダウンロードに失敗しました" : first.message);
        }
    }

    private static FallbackResult perform(Spec spec, String initialUrl, String username, String password,
                                          String credentialHost, String forcedReferer, int redirectCount, long downloadId) {
        if (redirectCount > MAX_REDIRECTS) {
            return FallbackResult.failure("リダイレクトが多すぎます");
        }
        HttpURLConnection connection = null;
        String currentUrl = initialUrl;
        String currentReferer = forcedReferer != null ? forcedReferer : spec.referer;
        Deque<String> visited = new ArrayDeque<>();
        int transientRetryCount = 0;
        try {
            while (true) {
                if (visited.contains(currentUrl)) {
                    return FallbackResult.failure("リダイレクトループを検出しました");
                }
                visited.addLast(currentUrl);
                if (visited.size() > MAX_REDIRECTS + 1) {
                    return FallbackResult.failure("リダイレクトが多すぎます");
                }
                URL url = new URL(currentUrl);
                String scheme = url.getProtocol();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return FallbackResult.failure("対応していないURLです");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                DownloadSupport.addHeader(connection, "Accept", "*/*");
                DownloadSupport.addHeader(connection, "Accept-Encoding", "identity");
                DownloadSupport.addHeader(connection, "User-Agent", spec.userAgent);
                if (DownloadSupport.isSafeHeaderValue(currentReferer) && BrowserUrlRouter.isWebUrl(currentReferer)) {
                    DownloadSupport.addHeader(connection, "Referer", currentReferer);
                }
                String cookie = null;
                try {
                    cookie = CookieManager.getInstance().getCookie(currentUrl);
                } catch (Exception ignored) {
                }
                DownloadSupport.addHeader(connection, "Cookie", cookie);
                String authorization = null;
                if (username != null && password != null && hostEquals(credentialHost, url.getHost())) {
                    authorization = BasicAuthManager.buildAuthorizationHeader(username, password);
                } else if (spec.basicAuthEnabled) {
                    authorization = BasicAuthManager.getAuthorizationHeaderForUrl(currentUrl, true);
                }
                DownloadSupport.addHeader(connection, "Authorization", authorization);
                int status = connection.getResponseCode();
                storeCookies(currentUrl, connection);
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (DownloadSupport.isBlank(location)) {
                        return FallbackResult.failure("リダイレクト先を取得できません");
                    }
                    String next = URI.create(currentUrl).resolve(location).toString();
                    if (username != null && password != null && credentialHost != null
                            && isCrossHost(currentUrl, next) && !hostEquals(credentialHost, hostOf(next))) {
                        username = null;
                        password = null;
                    }
                    currentReferer = currentUrl;
                    currentUrl = next;
                    redirectCount++;
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    String challenge = connection.getHeaderField("WWW-Authenticate");
                    String realm = parseBasicRealm(challenge);
                    if (spec.basicAuthEnabled && realm != null) {
                        return FallbackResult.authRequired(realm, currentUrl);
                    }
                    return FallbackResult.failure("認証が必要です");
                }
                if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                    return FallbackResult.failure("サーバーにダウンロードを拒否されました");
                }
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    return FallbackResult.failure("ダウンロードファイルが見つかりません");
                }
                if ((status == 429 || status == 502 || status == 503 || status == 504) && transientRetryCount < 1) {
                    transientRetryCount++;
                    long waitMs = parseRetryAfterMillis(connection.getHeaderField("Retry-After"));
                    connection.disconnect();
                    connection = null;
                    if (waitMs > 0) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return FallbackResult.failure("ダウンロードを中断しました");
                        }
                    }
                    continue;
                }
                if (status < 200 || status >= 300) {
                    return FallbackResult.failure("HTTPエラー: " + status);
                }
                String responseMime = connection.getContentType();
                String finalMime = DownloadSupport.normalizeMimeType(responseMime, currentUrl);
                String finalDisposition = connection.getHeaderField("Content-Disposition");
                if (DownloadSupport.isBlank(finalDisposition)) {
                    finalDisposition = connection.getHeaderField("X-Content-Disposition");
                }
                String fileName = DownloadSupport.resolveFileName(currentUrl, finalDisposition, finalMime);
                if (isWeakFallbackName(fileName)) {
                    String headerName = connection.getHeaderField("X-File-Name");
                    if (DownloadSupport.isBlank(headerName)) headerName = connection.getHeaderField("X-Download-Name");
                    if (DownloadSupport.isBlank(headerName)) headerName = connection.getHeaderField("X-Filename");
                    if (!DownloadSupport.isBlank(headerName)) fileName = DownloadSupport.sanitizeFileName(headerName);
                }
                if (isWeakFallbackName(fileName) && !DownloadSupport.isBlank(spec.initialFileName)) {
                    fileName = spec.initialFileName;
                }
                long responseLength = connection.getContentLengthLong();
                InputStream source = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                byte[] prefix = new byte[4096];
                int prefixLength = 0;
                while (prefixLength < prefix.length) {
                    int read = source.read(prefix, prefixLength, prefix.length - prefixLength);
                    if (read < 0) break;
                    if (read == 0) continue;
                    prefixLength += read;
                    if (prefixLength >= 512) break;
                }
                if (DownloadSupport.looksLikeHtml(prefix, prefixLength)
                        && !finalMime.startsWith("text/html") && !finalMime.startsWith("application/xhtml+xml")) {
                    try {
                        source.close();
                    } catch (Exception ignored) {
                    }
                    return FallbackResult.failure("サーバーがHTMLエラーページを返しました");
                }
                fileName = DownloadSupport.refineFileName(fileName, currentUrl, finalMime, prefix, prefixLength);
                if ((isOpaqueGeneratedName(fileName) || isExtensionless(fileName)) && !DownloadSupport.isBlank(spec.initialFileName)) {
                    String preferred = DownloadSupport.refineFileName(spec.initialFileName, spec.url, finalMime, prefix, prefixLength);
                    if (!DownloadSupport.isBlank(preferred)) {
                        fileName = preferred;
                    }
                }
                if (DownloadSupport.isBlank(fileName)) {
                    fileName = "download";
                }
                fileName = DownloadSupport.resolveUniqueDownloadFileName(spec.activity, fileName);
                long progressTotal = responseLength > 0 ? responseLength : spec.contentLength;
                if (responseLength > 0) {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    long free = dir.getUsableSpace();
                    if (free > 0 && responseLength > free) {
                        return FallbackResult.failure("空き容量が不足しています");
                    }
                }
                PublicStorageWriter.Target target = null;
                try {
                    target = PublicStorageWriter.openDownloadsTarget(spec.activity, fileName, finalMime);
                    target.outputStream.write(prefix, 0, prefixLength);
                    copyAndValidate(source, target.outputStream, prefixLength, responseLength, spec.activity,
                            downloadId, fileName, target.displayPath, finalMime, fileName);
                    PublicStorageWriter.finish(spec.activity, target);
                    long finalBytes = responseLength > 0 ? responseLength : progressTotal;
                    DownloadHistoryManager.updateManualDownload(spec.activity, downloadId, fileName, target.displayPath, target.localUri,
                            DownloadManager.STATUS_SUCCESSFUL, finalBytes, finalBytes);
                    toast(spec.activity, "ダウンロードを完了しました");
                    return FallbackResult.success();
                } catch (Exception e) {
                    if (target != null) {
                        PublicStorageWriter.abort(spec.activity, target);
                    }
                    return FallbackResult.failure("保存に失敗しました");
                }
            }
        } catch (Exception e) {
            return FallbackResult.failure("通信に失敗しました");
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void copyAndValidate(InputStream source, OutputStream target, long initialBytes, long strictTotal, Activity activity,
                                        long downloadId, String fileName, String displayPath,
                                        String mimeType, String effectiveFileName) throws IOException {
        try (BufferedInputStream in = source;
             BufferedOutputStream out = new BufferedOutputStream(target, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long done = initialBytes;
            long lastUpdate = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                done += read;
                long now = System.currentTimeMillis();
                if (now - lastUpdate >= PROGRESS_UPDATE_MS) {
                    lastUpdate = now;
                    DownloadHistoryManager.updateManualDownload(activity, downloadId, fileName, displayPath, null,
                            DownloadManager.STATUS_RUNNING, done, strictTotal > 0 ? strictTotal : done);
                }
            }
            out.flush();
            if (strictTotal > 0 && done != strictTotal) {
                throw new IOException("size mismatch");
            }
            DownloadHistoryManager.updateManualDownload(activity, downloadId, fileName, displayPath, null,
                    DownloadManager.STATUS_SUCCESSFUL, done, strictTotal > 0 ? strictTotal : done);
        }
    }

    private static void storeCookies(String url, HttpURLConnection connection) {
        try {
            List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
            if (cookies == null) {
                cookies = connection.getHeaderFields().get("set-cookie");
            }
            if (cookies != null) {
                CookieManager manager = CookieManager.getInstance();
                for (String cookie : cookies) {
                    if (!DownloadSupport.isBlank(cookie)) {
                        manager.setCookie(url, cookie);
                    }
                }
                manager.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static long parseRetryAfterMillis(String value) {
        if (DownloadSupport.isBlank(value)) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds <= 0) {
                return 0L;
            }
            return Math.min(seconds * 1000L, 5000L);
        } catch (Exception ignored) {
            return 1000L;
        }
    }

    private static String parseBasicRealm(String challenge) {
        if (DownloadSupport.isBlank(challenge)) {
            return null;
        }
        String lower = challenge.toLowerCase(java.util.Locale.ROOT);
        int basic = lower.indexOf("basic");
        if (basic < 0) {
            return null;
        }
        int realm = lower.indexOf("realm=", basic);
        if (realm < 0) {
            return "";
        }
        String value = challenge.substring(realm + 6).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            if (end > 0) {
                return value.substring(1, end);
            }
        }
        int comma = value.indexOf(',');
        return comma >= 0 ? value.substring(0, comma).trim() : value;
    }

    private static boolean isCrossHost(String first, String second) {
        try {
            Uri a = Uri.parse(first);
            Uri b = Uri.parse(second);
            String ah = a.getHost();
            String bh = b.getHost();
            return ah == null ? bh != null : !ah.equalsIgnoreCase(bh);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean hostEquals(String expected, String actual) {
        if (DownloadSupport.isBlank(expected) || DownloadSupport.isBlank(actual)) {
            return false;
        }
        return expected.equalsIgnoreCase(actual);
    }

    private static boolean isWeakFallbackName(String value) {
        if (DownloadSupport.isBlank(value)) return true;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return "download".equals(lower) || "file".equals(lower) || "untitled".equals(lower);
    }

    private static boolean isHtmlExtension(String ext) {
        return ".html".equalsIgnoreCase(ext) || ".htm".equalsIgnoreCase(ext) || ".xhtml".equalsIgnoreCase(ext);
    }

    private static boolean isBinaryLikeDownload(String fileName, String mimeType) {
        if (!DownloadSupport.isBlank(mimeType)) {
            String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("text/") || lower.contains("html") || lower.contains("xml")) {
                return false;
            }
        }
        String lowerName = DownloadSupport.isBlank(fileName) ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return lowerName.endsWith(".apk") || lowerName.endsWith(".xapk") || lowerName.endsWith(".apks")
                || lowerName.endsWith(".zip") || lowerName.endsWith(".7z") || lowerName.endsWith(".rar")
                || lowerName.endsWith(".gz") || lowerName.endsWith(".tar") || lowerName.endsWith(".pdf")
                || lowerName.endsWith(".bin") || lowerName.endsWith(".exe") || lowerName.endsWith(".dmg")
                || lowerName.endsWith(".mp4") || lowerName.endsWith(".mp3") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp");
    }

    private static boolean isExtensionless(String value) {
        if (DownloadSupport.isBlank(value)) return true;
        return !DownloadSupport.hasRecognizedDownloadExtension(value);
    }

    private static boolean isOpaqueGeneratedName(String value) {
        if (DownloadSupport.isBlank(value)) return true;
        String base = value;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.matches("(?i)[0-9a-f]{24,}") || base.matches("[0-9a-f]{8,}[-_][0-9a-f-]{12,}")
                || base.matches("[A-Za-z0-9_-]{32,}");
    }

    private static String hostOf(String url) {
        try {
            return Uri.parse(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extension(String fileName) {
        if (DownloadSupport.isBlank(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private static void toast(Activity activity, String message) {
        if (activity == null) {
            return;
        }
        UiThread.post(() -> {
            try {
                if (!isActivityUnavailable(activity)) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static boolean isActivityUnavailable(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return true;
        }
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed();
    }

    private static final class FallbackResult {
        final boolean success;
        final boolean needsCredentials;
        final String realm;
        final String authUrl;
        final String message;

        private FallbackResult(boolean success, boolean needsCredentials, String realm, String authUrl, String message) {
            this.success = success;
            this.needsCredentials = needsCredentials;
            this.realm = realm;
            this.authUrl = authUrl;
            this.message = message;
        }

        static FallbackResult success() {
            return new FallbackResult(true, false, null, null, null);
        }

        static FallbackResult failure(String message) {
            return new FallbackResult(false, false, null, null, message);
        }

        static FallbackResult authRequired(String realm, String authUrl) {
            return new FallbackResult(false, true, realm, authUrl, null);
        }
    }
}
