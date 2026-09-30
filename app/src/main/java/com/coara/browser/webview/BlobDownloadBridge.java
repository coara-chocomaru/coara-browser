package com.coara.browser.webview;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.coara.browser.BrowserApplication;
import com.coara.browser.DownloadHistoryActivity;
import com.coara.browser.DownloadHistoryManager;
import com.coara.browser.util.DownloadSupport;
import com.coara.browser.util.ExternalDownloadTabTracker;
import com.coara.browser.util.DownloadHintStore;
import com.coara.browser.util.PublicStorageWriter;
import com.coara.browser.util.UiThread;

import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BlobDownloadBridge {
    private static final AtomicInteger NOTIFICATION_ID_SEQ = new AtomicInteger(20000);
    private static final AtomicInteger SESSION_ID_SEQ = new AtomicInteger(1);
    private static final long NOTIFY_INTERVAL_MS = 500;
    private static final int MAX_BASE64_CHUNK_LENGTH = 1024 * 1024;

    private final Context context;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, CloseRequest> closeRequests = new ConcurrentHashMap<>();

    private static final class CloseRequest {
        final WeakReference<Activity> activity;
        final WeakReference<WebView> webView;
        final String downloadUrl;
        final Runnable closeAction;
        CloseRequest(Activity activity, WebView webView, String downloadUrl, Runnable closeAction) {
            this.activity = new WeakReference<>(activity);
            this.webView = new WeakReference<>(webView);
            this.downloadUrl = downloadUrl;
            this.closeAction = closeAction;
        }
    }

    public BlobDownloadBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void registerCloseAfterDownload(String token, Activity activity, WebView webView,
                                           String downloadUrl, Runnable closeAction) {
        if (DownloadSupport.isBlank(token) || activity == null || webView == null || closeAction == null) {
            return;
        }
        closeRequests.put(token, new CloseRequest(activity, webView, downloadUrl, closeAction));
    }

    public void shutdown() {
        for (Map.Entry<String, Session> entry : new java.util.ArrayList<>(sessions.entrySet())) {
            Session session = entry.getValue();
            if (session != null) {
                try {
                    session.writer.shutdownNow();
                } catch (Exception ignored) {
                }
                PublicStorageWriter.abort(context, session.target);
            }
        }
        sessions.clear();
        closeRequests.clear();
    }

    private static final class Session {
        volatile PublicStorageWriter.Target target;
        volatile long downloadId;
        volatile String fileName;
        volatile long totalSize;
        volatile long bytesWritten;
        volatile int notificationId;
        volatile long lastNotifyTime;
        volatile boolean failed;
        volatile boolean completed;
        final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CoaraBlobWriter-" + SESSION_ID_SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    @JavascriptInterface
    public void rememberDownloadHint(String url, String fileName) {
        DownloadHintStore.remember(url, fileName);
    }

    @JavascriptInterface
    public void onBlobStart(String token, String fileName, String mimeType, String totalSizeStr) {
        onBlobStart(token, fileName, mimeType, totalSizeStr, null);
    }

    @JavascriptInterface
    public void onBlobStart(String token, String fileName, String mimeType, String totalSizeStr,
                            String contentDisposition) {
        if (DownloadSupport.isBlank(token)) {
            return;
        }
        Session session = new Session();
        try {
            long totalSize = 0;
            try {
                totalSize = Long.parseLong(totalSizeStr);
            } catch (Exception ignored) {
            }
            String effectiveMime = DownloadSupport.normalizeMimeType(mimeType, null);
            String effectiveName = DownloadSupport.resolveFileName(
                    "https://blob.invalid/" + (DownloadSupport.isBlank(fileName) ? "download" : fileName),
                    contentDisposition, effectiveMime);
            if (DownloadSupport.isBlank(effectiveName) || "download".equalsIgnoreCase(effectiveName)) {
                effectiveName = DownloadSupport.isBlank(fileName) ? DownloadSupport.buildTimestampFileName("blob_download_", effectiveMime) : fileName;
            }
            effectiveName = DownloadSupport.resolveUniqueDownloadFileName(context, effectiveName);
            if (totalSize > 0) {
                java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                long free = dir.getUsableSpace();
                if (free > 0 && totalSize > free) {
                    throw new java.io.IOException("insufficient storage");
                }
            }
            PublicStorageWriter.Target target = PublicStorageWriter.openDownloadsTarget(context, effectiveName, effectiveMime);
            session.target = target;
            session.downloadId = DownloadHistoryManager.nextManualDownloadId();
            session.fileName = effectiveName;
            session.totalSize = totalSize;
            session.notificationId = NOTIFICATION_ID_SEQ.incrementAndGet();
            Session previous = sessions.get(token);
            if (previous != null && !previous.completed && !previous.failed) {
                failSession(token, previous);
            }
            sessions.put(token, session);
            DownloadHistoryManager.addDownloadHistory(context, session.downloadId, session.fileName,
                    target.displayPath, null, null, false);
            DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                    target.displayPath, target.localUri, DownloadManager.STATUS_RUNNING, 0, totalSize);
            postProgressNotification(session);
        } catch (Exception e) {
            session.failed = true;
            sessions.remove(token);
            closeRequests.remove(token);
            PublicStorageWriter.abort(context, session.target);
            session.writer.shutdownNow();
            postToast("ダウンロード開始に失敗しました");
        }
    }

    @JavascriptInterface
    public void onBlobChunk(String token, String base64Chunk) {
        Session session = sessions.get(token);
        if (session == null || session.failed || session.completed || DownloadSupport.isBlank(base64Chunk)
                || base64Chunk.length() > MAX_BASE64_CHUNK_LENGTH) {
            return;
        }
        session.writer.execute(() -> {
            synchronized (session) {
                if (session.failed || session.completed || session.target == null) {
                    return;
                }
                try {
                    byte[] data = Base64.decode(base64Chunk, Base64.DEFAULT);
                    session.target.outputStream.write(data);
                    session.bytesWritten += data.length;
                    long now = System.currentTimeMillis();
                    if (now - session.lastNotifyTime >= NOTIFY_INTERVAL_MS) {
                        session.lastNotifyTime = now;
                        postProgressNotification(session);
                        DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                                session.target.displayPath, session.target.localUri, DownloadManager.STATUS_RUNNING,
                                session.bytesWritten, session.totalSize);
                    }
                } catch (Exception e) {
                    failSession(token, session);
                }
            }
        });
    }

    @JavascriptInterface
    public void onBlobComplete(String token) {
        Session session = sessions.get(token);
        if (session == null) {
            return;
        }
        session.writer.execute(() -> {
            synchronized (session) {
                if (session.failed || session.completed || session.target == null) {
                    return;
                }
                try {
                    PublicStorageWriter.finish(context, session.target);
                    session.completed = true;
                    sessions.remove(token);
                    session.writer.shutdown();
                    CloseRequest closeRequest = closeRequests.remove(token);
                    DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                            session.target.displayPath, session.target.localUri, DownloadManager.STATUS_SUCCESSFUL,
                            session.bytesWritten, session.bytesWritten);
                    postCompleteNotification(session);
                    scheduleClose(closeRequest);
                } catch (Exception e) {
                    failSession(token, session);
                }
            }
        });
    }

    @JavascriptInterface
    public void onBlobDownloadError(String errorMessage) {
        postToast("blob ダウンロードエラー: " + (DownloadSupport.isBlank(errorMessage) ? "unknown" : errorMessage));
    }

    @JavascriptInterface
    public void onBlobError(String token, String message) {
        Session session = sessions.get(token);
        if (session == null) {
            postToast("ダウンロードに失敗しました");
            return;
        }
        session.writer.execute(() -> failSession(token, session));
    }

    private void failSession(String token, Session session) {
        synchronized (session) {
            if (session.failed) {
                return;
            }
            session.failed = true;
            sessions.remove(token);
            closeRequests.remove(token);
            PublicStorageWriter.abort(context, session.target);
            DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                    session.target != null ? session.target.displayPath : "",
                    session.target != null ? session.target.localUri : "", DownloadManager.STATUS_FAILED,
                    session.bytesWritten, session.totalSize);
            postFailedNotification(session);
            session.writer.shutdown();
        }
        postToast("ダウンロードに失敗しました");
    }

    private void scheduleClose(CloseRequest request) {
        if (request == null) {
            return;
        }
        Activity activity = request.activity.get();
        WebView webView = request.webView.get();
        if (activity == null || webView == null || request.closeAction == null) {
            return;
        }
        UiThread.postDelayed(() -> {
            try {
                if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                    return;
                }
                if (ExternalDownloadTabTracker.shouldCloseAfterDownload(webView, request.downloadUrl)) {
                    request.closeAction.run();
                }
            } catch (Exception ignored) {
            }
        }, 750L);
    }

    private void postToast(String message) {
        UiThread.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private void postProgressNotification(Session session) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(session.fileName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (session.totalSize > 0) {
            int percent = (int) Math.min(100, (session.bytesWritten * 100) / session.totalSize);
            builder.setProgress(100, percent, false);
            builder.setContentText(percent + "%");
        } else {
            builder.setProgress(0, 0, true);
            builder.setContentText(formatBytes(session.bytesWritten));
        }
        showNotification(session.notificationId, builder);
    }

    private void postCompleteNotification(Session session) {
        Intent intent = new Intent(context, DownloadHistoryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, session.notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(session.fileName)
                .setContentText("ダウンロード完了")
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        showNotification(session.notificationId, builder);
    }

    private void postFailedNotification(Session session) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, BrowserApplication.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(session.fileName == null ? "ダウンロード" : session.fileName)
                .setContentText("ダウンロード失敗")
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        showNotification(session.notificationId, builder);
    }

    private void showNotification(int id, NotificationCompat.Builder builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (Exception ignored) {
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.getDefault(), "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.getDefault(), "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(java.util.Locale.getDefault(), "%.2f GB", gb);
    }
}
