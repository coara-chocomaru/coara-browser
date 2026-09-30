package com.coara.browser.util;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.coara.browser.BrowserApplication;
import com.coara.browser.DownloadHistoryActivity;
import com.coara.browser.DownloadHistoryManager;
import com.coara.browser.model.DownloadItem;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicLong;
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
    private static final ConcurrentHashMap<Long, DownloadControl> CONTROLS = new ConcurrentHashMap<>();
    private static final AtomicLong CONTROL_GENERATION = new AtomicLong(0L);
    private static final ConcurrentHashMap<Long, HttpURLConnection> ACTIVE_CONNECTIONS = new ConcurrentHashMap<>();

    private static final class DownloadControl {
        volatile boolean paused;
        volatile boolean cancelled;
        volatile long generation;
    }

    private DownloadFallbackManager() {
    }

    private static DownloadControl newControl() {
        DownloadControl control = new DownloadControl();
        control.generation = CONTROL_GENERATION.incrementAndGet();
        return control;
    }

    public static boolean isActive(long downloadId) {
        DownloadControl control = CONTROLS.get(downloadId);
        return control != null && !control.cancelled && !control.paused;
    }

    public static long getGeneration(long downloadId) {
        DownloadControl control = CONTROLS.get(downloadId);
        return control == null ? -1L : control.generation;
    }

    public static long ensureGeneration(Context context, long downloadId) {
        DownloadControl control = CONTROLS.get(downloadId);
        if (control != null) return control.generation;
        DownloadItem item = DownloadHistoryManager.getManualDownloadItem(context, downloadId);
        if (item == null || item.isPaused || DownloadSupport.isBlank(item.downloadUrl)) return -1L;
        DownloadControl created = newControl();
        DownloadControl existing = CONTROLS.putIfAbsent(downloadId, created);
        return existing == null ? created.generation : existing.generation;
    }

    private static boolean isCurrentGeneration(long downloadId, long generation) {
        DownloadControl control = CONTROLS.get(downloadId);
        return generation < 0L || (control != null && control.generation == generation);
    }

    public static void startDownloadService(Context context, long downloadId) {
        if (context == null || downloadId <= 0) return;
        try {
            Context appContext = context.getApplicationContext();
            Intent intent = new Intent(appContext, DownloadFallbackService.class);
            intent.putExtra(DownloadFallbackService.EXTRA_DOWNLOAD_ID, downloadId);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            EXECUTOR.execute(() -> runPersistedDownload(context, downloadId));
        }
    }

    public static boolean runPersistedDownload(Context context, long downloadId) {
        if (context == null || downloadId <= 0) return false;
        Context appContext = context.getApplicationContext();
        DownloadControl control = CONTROLS.get(downloadId);
        if (control == null) {
            control = newControl();
            DownloadControl existing = CONTROLS.putIfAbsent(downloadId, control);
            if (existing != null) control = existing;
        }
        if (control.cancelled || control.paused) return false;
        long generation = control.generation;
        Spec spec = SPECS.get(downloadId);
        if (spec == null) {
            DownloadItem item = DownloadHistoryManager.getManualDownloadItem(appContext, downloadId);
            if (item == null || item.isPaused || DownloadSupport.isBlank(item.downloadUrl)) return false;
            String mime = DownloadSupport.normalizeMimeType(null, item.title);
            spec = new Spec(null, null, item.downloadUrl, item.userAgent, null, mime, item.totalSize,
                    item.referer, item.title, mime, item.basicAuthEnabled);
            SPECS.put(downloadId, spec);
        }
        runFallback(spec, downloadId, appContext, generation);
        return true;
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
            DownloadHistoryManager.addDownloadHistory(activity, id, fileName, filePath, userAgent, referer, basicAuthEnabled,
                    url, effectiveMime, contentDisposition);
            DownloadHistoryManager.updateManualDownloadMetadata(activity, id, url, userAgent, referer, effectiveMime,
                    contentDisposition, basicAuthEnabled, fileName, filePath, null);
            DownloadHistoryManager.updateManualDownload(activity, id, fileName, filePath,
                    DownloadManager.STATUS_RUNNING, 0L, contentLength);
            Spec spec = new Spec(activity, webView, url, userAgent, contentDisposition, mimeType, contentLength,
                    referer, fileName, effectiveMime, basicAuthEnabled);
            DownloadControl control = newControl();
            CONTROLS.put(id, control);
            SPECS.put(id, spec);
            postProgressNotification(activity, id, fileName, 0L, contentLength);
            startDownloadService(activity, id);
            return id;
        } catch (Exception e) {
            return -1L;
        }
    }

    public static void clear(long downloadId) {
        SPECS.remove(downloadId);
        CONTROLS.remove(downloadId);
        ACTIVE_CONNECTIONS.remove(downloadId);
    }

    public static boolean pauseDownload(Context context, long downloadId) {
        DownloadControl control = CONTROLS.get(downloadId);
        if (control == null) {
            return false;
        }
        control.paused = true;
        HttpURLConnection connection = ACTIVE_CONNECTIONS.get(downloadId);
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
        DownloadItem item = DownloadHistoryManager.getManualDownloadItem(context, downloadId);
        if (item != null) {
            DownloadHistoryManager.updateManualDownload(context, downloadId, item.title, item.filePath, item.localUri,
                    DownloadManager.STATUS_PAUSED, item.downloadedSize, item.totalSize);
            postPausedNotification(context, downloadId, item.title);
        }
        return true;
    }

    public static boolean cancelDownload(Context context, long downloadId) {
        DownloadControl control = CONTROLS.get(downloadId);
        if (control == null) {
            return false;
        }
        control.cancelled = true;
        HttpURLConnection connection = ACTIVE_CONNECTIONS.get(downloadId);
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
        DownloadItem item = DownloadHistoryManager.getManualDownloadItem(context, downloadId);
        if (item != null) {
            DownloadHistoryManager.updateManualDownload(context, downloadId, item.title, item.filePath, item.localUri,
                    DownloadManager.STATUS_FAILED, item.downloadedSize, item.totalSize);
            postFailedNotification(context, downloadId, item.title, "ダウンロードをキャンセルしました");
        }
        return true;
    }

    public static boolean resumeDownload(Context context, long downloadId) {
        DownloadItem item = DownloadHistoryManager.getManualDownloadItem(context, downloadId);
        if (item == null || !item.manual || DownloadSupport.isBlank(item.downloadUrl)) {
            return false;
        }
        DownloadControl control = CONTROLS.get(downloadId);
        if (control == null) {
            control = newControl();
            DownloadControl existing = CONTROLS.putIfAbsent(downloadId, control);
            if (existing != null) control = existing;
        }
        control.generation = CONTROL_GENERATION.incrementAndGet();
        control.paused = false;
        control.cancelled = false;
        SPECS.remove(downloadId);
        DownloadHistoryManager.updateManualDownload(context, downloadId, item.title, item.filePath, item.localUri,
                DownloadManager.STATUS_RUNNING, 0L, item.totalSize);
        postProgressNotification(context, downloadId, item.title, 0L, item.totalSize);
        startDownloadService(context, downloadId);
        return true;
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
        if (DownloadHistoryManager.getManualDownloadStatus(context, downloadId) == DownloadManager.STATUS_PAUSED) {
            return;
        }
        Spec spec = SPECS.remove(downloadId);
        if (spec == null || !shouldFallback(reason)) {
            return;
        }
        CONTROLS.putIfAbsent(downloadId, newControl());
        removeDownload(dm, downloadId);
        startDownloadService(context, downloadId);
    }

    public static void onPendingTimeout(Context context, DownloadManager dm, long downloadId) {
        if (DownloadHistoryManager.getManualDownloadStatus(context, downloadId) == DownloadManager.STATUS_PAUSED) {
            return;
        }
        Spec spec = SPECS.remove(downloadId);
        if (spec == null) {
            return;
        }
        CONTROLS.putIfAbsent(downloadId, newControl());
        removeDownload(dm, downloadId);
        startDownloadService(context, downloadId);
    }

    public static void onSuspiciousSuccessfulDownload(Context context, DownloadManager dm, long downloadId, String localUri) {
        Spec spec = SPECS.remove(downloadId);
        if (spec == null) {
            return;
        }
        CONTROLS.putIfAbsent(downloadId, newControl());
        DownloadSupport.deleteLocalUri(context, localUri);
        removeDownload(dm, downloadId);
        startDownloadService(context, downloadId);
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
        DownloadControl control = CONTROLS.get(downloadId);
        long generation = control == null ? -1L : control.generation;
        runFallback(spec, downloadId, spec.activity, generation);
    }

    private static void runFallback(Spec spec, long downloadId, Context context) {
        DownloadControl control = CONTROLS.get(downloadId);
        long generation = control == null ? -1L : control.generation;
        runFallback(spec, downloadId, context, generation);
    }

    private static void runFallback(Spec spec, long downloadId, Context context, long generation) {
        final Context appContext = context != null ? context.getApplicationContext() : (spec.activity != null ? spec.activity.getApplicationContext() : null);
        if (appContext == null || !isCurrentGeneration(downloadId, generation)) {
            return;
        }
        if (spec.activity != null && isActivityUnavailable(spec.activity)) {
            DownloadHistoryManager.updateManualDownload(appContext, downloadId, spec.initialFileName, "",
                    DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
            postFailedNotification(appContext, downloadId, spec.initialFileName, "ダウンロードを開始できませんでした");
            CONTROLS.remove(downloadId);
            SPECS.remove(downloadId);
            return;
        }
        DownloadHistoryManager.updateManualDownloadMetadata(appContext, downloadId, spec.url, spec.userAgent, spec.referer,
                spec.effectiveMimeType, spec.contentDisposition, spec.basicAuthEnabled, spec.initialFileName, "", null);
        DownloadHistoryManager.updateManualDownload(appContext, downloadId, spec.initialFileName, "",
                DownloadManager.STATUS_RUNNING, 0L, spec.contentLength);
        postProgressNotification(appContext, downloadId, spec.initialFileName, 0L, spec.contentLength);
        FallbackResult first = perform(spec, spec.url, null, null, null, null, 0, downloadId, appContext, generation);
        if (first.needsCredentials && spec.basicAuthEnabled && spec.activity != null) {
            final String realm = first.realm == null ? "" : first.realm;
            final String authHost = hostOf(first.authUrl == null ? spec.url : first.authUrl);
            BasicAuthManager.markAuthenticationFailure(spec.activity, spec.webView, authHost, realm);
            UiThread.post(() -> BasicAuthManager.requestCredentials(
                    spec.activity, spec.webView, authHost, realm,
                    (username, password) -> {
                        if (username == null || password == null) {
                            DownloadHistoryManager.updateManualDownload(appContext, downloadId, spec.initialFileName, "",
                                    DownloadManager.STATUS_FAILED, 0L, spec.contentLength);
                            postFailedNotification(appContext, downloadId, spec.initialFileName, "Basic認証をキャンセルしました");
                            toast(spec.activity, "Basic認証をキャンセルしました");
                            CONTROLS.remove(downloadId);
                            SPECS.remove(downloadId);
                            return;
                        }
                        EXECUTOR.execute(() -> {
                            FallbackResult second = perform(spec, spec.url, username, password, authHost, null, 0, downloadId, appContext, generation);
                            if (!isCurrentGeneration(downloadId, generation)) {
                                return;
                            }
                            if (!second.success) {
                                int status = second.paused ? DownloadManager.STATUS_PAUSED : DownloadManager.STATUS_FAILED;
                                DownloadItem item = DownloadHistoryManager.getManualDownloadItem(appContext, downloadId);
                                long done = item == null ? 0L : item.downloadedSize;
                                long total = item == null ? spec.contentLength : item.totalSize;
                                String name = item == null ? spec.initialFileName : item.title;
                                String path = item == null ? "" : item.filePath;
                                String uri = item == null ? "" : item.localUri;
                                DownloadHistoryManager.updateManualDownload(appContext, downloadId, name, path, uri, status, done, total);
                                if (status == DownloadManager.STATUS_PAUSED) {
                                    postPausedNotification(appContext, downloadId, name);
                                } else {
                                    postFailedNotification(appContext, downloadId, name, second.message == null ? "ダウンロードに失敗しました" : second.message);
                                    toast(spec.activity, second.message == null ? "ダウンロードに失敗しました" : second.message);
                                }
                                CONTROLS.remove(downloadId);
                                SPECS.remove(downloadId);
                            }
                        });
                    }));
            return;
        }
        if (!isCurrentGeneration(downloadId, generation)) {
            return;
        }
        if (!first.success) {
            int status = first.paused ? DownloadManager.STATUS_PAUSED : DownloadManager.STATUS_FAILED;
            DownloadItem item = DownloadHistoryManager.getManualDownloadItem(appContext, downloadId);
            long done = item == null ? 0L : item.downloadedSize;
            long total = item == null ? spec.contentLength : item.totalSize;
            String name = item == null ? spec.initialFileName : item.title;
            String path = item == null ? "" : item.filePath;
            String uri = item == null ? "" : item.localUri;
            DownloadHistoryManager.updateManualDownload(appContext, downloadId, name, path, uri, status, done, total);
            if (status == DownloadManager.STATUS_PAUSED) {
                postPausedNotification(appContext, downloadId, name);
            } else if (first.cancelled) {
                postFailedNotification(appContext, downloadId, name, "ダウンロードをキャンセルしました");
            } else {
                postFailedNotification(appContext, downloadId, name, first.message == null ? "ダウンロードに失敗しました" : first.message);
                toast(spec.activity, first.message == null ? "ダウンロードに失敗しました" : first.message);
            }
            CONTROLS.remove(downloadId);
            SPECS.remove(downloadId);
        }
    }

    private static FallbackResult perform(Spec spec, String initialUrl, String username, String password,
                                          String credentialHost, String forcedReferer, int redirectCount, long downloadId, Context context, long generation) {
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
                if (!isCurrentGeneration(downloadId, generation)) {
                    return FallbackResult.paused();
                }
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
                ACTIVE_CONNECTIONS.put(downloadId, connection);
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
                DownloadControl control = CONTROLS.get(downloadId);
                if (control != null && control.cancelled) {
                    return FallbackResult.cancelled("ダウンロードをキャンセルしました");
                }
                if (control != null && control.paused) {
                    return FallbackResult.paused();
                }
                String responseMime = connection.getContentType();
                String finalDisposition = connection.getHeaderField("Content-Disposition");
                String alternateDisposition = connection.getHeaderField("X-Content-Disposition");
                String headerName = connection.getHeaderField("X-File-Name");
                if (DownloadSupport.isBlank(headerName)) headerName = connection.getHeaderField("X-Download-Name");
                if (DownloadSupport.isBlank(headerName)) headerName = connection.getHeaderField("X-Filename");
                String contentLocation = connection.getHeaderField("Content-Location");
                String finalMime = DownloadSupport.normalizeMimeType(responseMime, currentUrl);
                long responseLength = connection.getContentLengthLong();
                BufferedInputStream source = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                byte[] prefix = new byte[4096];
                int prefixLength = 0;
                while (prefixLength < prefix.length) {
                    int read = source.read(prefix, prefixLength, prefix.length - prefixLength);
                    if (read < 0) break;
                    if (read == 0) continue;
                    prefixLength += read;
                    if (prefixLength >= 512) break;
                }
                finalMime = DownloadSupport.refineMimeType(finalMime, currentUrl, prefix, prefixLength);
                if (DownloadSupport.looksLikeHtml(prefix, prefixLength)
                        && !finalMime.startsWith("text/html") && !finalMime.startsWith("application/xhtml+xml")) {
                    try {
                        source.close();
                    } catch (Exception ignored) {
                    }
                    return FallbackResult.failure("サーバーがHTMLエラーページを返しました");
                }
                if (prefixLength == 0 && (isBinaryLikeDownload(spec.initialFileName, finalMime)
                        || DownloadSupport.hasRecognizedDownloadExtension(spec.initialFileName))) {
                    try {
                        source.close();
                    } catch (Exception ignored) {
                    }
                    return FallbackResult.failure("ダウンロードデータが空です");
                }
                String fileName = DownloadSupport.resolveResponseFileName(currentUrl, finalDisposition, responseMime,
                        contentLocation, alternateDisposition);
                if (!DownloadSupport.isBlank(headerName)) {
                    String headerCandidate = DownloadSupport.resolveResponseFileName(currentUrl, null, responseMime, null, headerName);
                    if (isWeakFallbackName(fileName) || isOpaqueFallbackName(fileName)) {
                        fileName = headerCandidate;
                    }
                }
                if ((isWeakFallbackName(fileName) || isOpaqueFallbackName(fileName)) && !DownloadSupport.isBlank(spec.initialFileName)) {
                    fileName = spec.initialFileName;
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
                fileName = DownloadSupport.resolveUniqueDownloadFileName(context, fileName);
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
                    target = PublicStorageWriter.openDownloadsTarget(context, fileName, finalMime);
                    target.outputStream.write(prefix, 0, prefixLength);
                    CopyResult copy = copyAndValidate(source, target.outputStream, prefixLength, responseLength, context,
                            downloadId, fileName, target.displayPath, finalMime, fileName, generation);
                    if (copy.cancelled) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.cancelled("ダウンロードをキャンセルしました");
                    }
                    if (copy.paused) {
                        PublicStorageWriter.abort(context, target);
                        DownloadHistoryManager.updateManualDownload(context, downloadId, fileName, target.displayPath, target.localUri,
                                DownloadManager.STATUS_PAUSED, copy.bytes, responseLength > 0 ? responseLength : progressTotal);
                        return FallbackResult.paused();
                    }
                    if (!isCurrentGeneration(downloadId, generation)) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.paused();
                    }
                    DownloadControl beforeFinish = CONTROLS.get(downloadId);
                    if (beforeFinish != null && beforeFinish.cancelled) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.cancelled("ダウンロードをキャンセルしました");
                    }
                    if (beforeFinish != null && beforeFinish.paused) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.paused();
                    }
                    if (responseLength > 0 && copy.bytes != responseLength) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.failure("ダウンロードサイズが一致しません");
                    }
                    if (!PublicStorageWriter.finish(context, target)) {
                        PublicStorageWriter.abort(context, target);
                        return FallbackResult.failure("保存を確定できませんでした");
                    }
                    long finalBytes = responseLength > 0 ? responseLength : progressTotal;
                    DownloadHistoryManager.updateManualDownloadMetadata(context, downloadId, spec.url, spec.userAgent, spec.referer,
                            finalMime, finalDisposition, spec.basicAuthEnabled, fileName, target.displayPath, target.localUri);
                    DownloadHistoryManager.updateManualDownload(context, downloadId, fileName, target.displayPath, target.localUri,
                            DownloadManager.STATUS_SUCCESSFUL, finalBytes, finalBytes);
                    postCompleteNotification(context, downloadId, fileName);
                    toast(spec.activity, "ダウンロードを完了しました");
                    CONTROLS.remove(downloadId);
                    SPECS.remove(downloadId);
                    return FallbackResult.success();
                } catch (Exception e) {
                    DownloadControl currentControl = CONTROLS.get(downloadId);
                    if (target != null && (currentControl == null || (!currentControl.paused && !currentControl.cancelled))) {
                        PublicStorageWriter.abort(context, target);
                    }
                    if (currentControl != null && currentControl.cancelled) return FallbackResult.cancelled("ダウンロードをキャンセルしました");
                    if (currentControl != null && currentControl.paused) return FallbackResult.paused();
                    return FallbackResult.failure("保存に失敗しました");
                }
            }
        } catch (Exception e) {
            DownloadControl currentControl = CONTROLS.get(downloadId);
            if (currentControl != null && currentControl.cancelled) return FallbackResult.cancelled("ダウンロードをキャンセルしました");
            if (currentControl != null && currentControl.paused) return FallbackResult.paused();
            return FallbackResult.failure("通信に失敗しました");
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
            ACTIVE_CONNECTIONS.remove(downloadId);
        }
    }

    private static CopyResult copyAndValidate(BufferedInputStream source, OutputStream target, long initialBytes, long strictTotal, Context context,
                                             long downloadId, String fileName, String displayPath,
                                             String mimeType, String effectiveFileName, long generation) throws IOException {
        long done = initialBytes;
        long lastUpdate = 0L;
        DownloadControl control = CONTROLS.get(downloadId);
        try (BufferedInputStream in = source;
             BufferedOutputStream out = new BufferedOutputStream(target, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) continue;
                if (!isCurrentGeneration(downloadId, generation)) {
                    return CopyResult.paused(done);
                }
                control = CONTROLS.get(downloadId);
                if (control != null && control.cancelled) {
                    return CopyResult.cancelled(done);
                }
                if (control != null && control.paused) {
                    return CopyResult.paused(done);
                }
                out.write(buffer, 0, read);
                done += read;
                long now = System.currentTimeMillis();
                if (now - lastUpdate >= PROGRESS_UPDATE_MS) {
                    lastUpdate = now;
                    long total = strictTotal > 0 ? strictTotal : done;
                    DownloadHistoryManager.updateManualDownload(context, downloadId, fileName, displayPath, null,
                            DownloadManager.STATUS_RUNNING, done, total);
                    postProgressNotification(context, downloadId, fileName, done, strictTotal);
                }
            }
            out.flush();
            if (!isCurrentGeneration(downloadId, generation)) {
                return CopyResult.paused(done);
            }
            control = CONTROLS.get(downloadId);
            if (control != null && control.cancelled) return CopyResult.cancelled(done);
            if (control != null && control.paused) return CopyResult.paused(done);
            return CopyResult.success(done);
        }
    }

    private static final class CopyResult {
        final long bytes;
        final boolean paused;
        final boolean cancelled;
        private CopyResult(long bytes, boolean paused, boolean cancelled) {
            this.bytes = bytes;
            this.paused = paused;
            this.cancelled = cancelled;
        }
        static CopyResult success(long bytes) { return new CopyResult(bytes, false, false); }
        static CopyResult paused(long bytes) { return new CopyResult(bytes, true, false); }
        static CopyResult cancelled(long bytes) { return new CopyResult(bytes, false, true); }
    }

    static int notificationId(long downloadId) {
        int hash = Long.valueOf(downloadId).hashCode() & 0x3fffffff;
        return 30000 + (hash % 1000000);
    }

    private static PendingIntent historyPendingIntent(Context context, long downloadId) {
        try {
            Intent intent = new Intent(context, DownloadHistoryActivity.class);
            intent.putExtra("download_id", downloadId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(context, notificationId(downloadId), intent, flags);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Notification buildProgressNotification(Context context, long downloadId, String fileName, long downloaded, long total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(DownloadSupport.isBlank(fileName) ? "ダウンロード" : fileName)
                .setContentText(total > 0 ? ((downloaded * 100L) / total) + "%" : formatBytes(downloaded))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        PendingIntent pendingIntent = historyPendingIntent(context, downloadId);
        if (pendingIntent != null) builder.setContentIntent(pendingIntent);
        if (total > 0) builder.setProgress(100, (int)Math.min(100L, (downloaded * 100L) / total), false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private static void postProgressNotification(Context context, long downloadId, String fileName, long downloaded, long total) {
        if (context == null) return;
        try {
            NotificationManagerCompat.from(context).notify(notificationId(downloadId),
                    buildProgressNotification(context, downloadId, fileName, downloaded, total));
        } catch (Exception ignored) {
        }
    }

    private static void postPausedNotification(Context context, long downloadId, String fileName) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(DownloadSupport.isBlank(fileName) ? "ダウンロード" : fileName)
                    .setContentText("ダウンロードを一時停止しました")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            PendingIntent pendingIntent = historyPendingIntent(context, downloadId);
            if (pendingIntent != null) builder.setContentIntent(pendingIntent);
            NotificationManagerCompat.from(context).notify(notificationId(downloadId), builder.build());
        } catch (Exception ignored) {
        }
    }

    private static void postCompleteNotification(Context context, long downloadId, String fileName) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(DownloadSupport.isBlank(fileName) ? "ダウンロード" : fileName)
                    .setContentText("ダウンロード完了")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            PendingIntent pendingIntent = historyPendingIntent(context, downloadId);
            if (pendingIntent != null) builder.setContentIntent(pendingIntent);
            NotificationManagerCompat.from(context).notify(notificationId(downloadId), builder.build());
        } catch (Exception ignored) {
        }
    }

    private static void postFailedNotification(Context context, long downloadId, String fileName, String message) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(DownloadSupport.isBlank(fileName) ? "ダウンロード" : fileName)
                    .setContentText(DownloadSupport.isBlank(message) ? "ダウンロード失敗" : message)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            PendingIntent pendingIntent = historyPendingIntent(context, downloadId);
            if (pendingIntent != null) builder.setContentIntent(pendingIntent);
            NotificationManagerCompat.from(context).notify(notificationId(downloadId), builder.build());
        } catch (Exception ignored) {
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb);
        return String.format(java.util.Locale.getDefault(), "%.2f GB", mb / 1024.0);
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
        return "download".equals(lower) || "file".equals(lower) || "untitled".equals(lower)
                || lower.startsWith("download.") || lower.startsWith("file.") || lower.startsWith("untitled.");
    }

    private static boolean isOpaqueFallbackName(String value) {
        if (DownloadSupport.isBlank(value)) return true;
        String base = value;
        String ext = extension(base);
        if (!DownloadSupport.isBlank(ext)) base = base.substring(0, base.length() - ext.length());
        return base.matches("(?i)[0-9a-f]{24,}") || base.matches("[0-9a-f]{8,}[-_][0-9a-f-]{12,}")
                || base.matches("[A-Za-z0-9_-]{32,}");
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
        final boolean paused;
        final boolean cancelled;

        private FallbackResult(boolean success, boolean needsCredentials, String realm, String authUrl, String message, boolean paused, boolean cancelled) {
            this.success = success;
            this.needsCredentials = needsCredentials;
            this.realm = realm;
            this.authUrl = authUrl;
            this.message = message;
            this.paused = paused;
            this.cancelled = cancelled;
        }

        static FallbackResult success() {
            return new FallbackResult(true, false, null, null, null, false, false);
        }

        static FallbackResult failure(String message) {
            return new FallbackResult(false, false, null, null, message, false, false);
        }

        static FallbackResult paused() {
            return new FallbackResult(false, false, null, null, "paused", true, false);
        }

        static FallbackResult cancelled(String message) {
            return new FallbackResult(false, false, null, null, message, false, true);
        }

        static FallbackResult authRequired(String realm, String authUrl) {
            return new FallbackResult(false, true, realm, authUrl, null, false, false);
        }
    }
}
