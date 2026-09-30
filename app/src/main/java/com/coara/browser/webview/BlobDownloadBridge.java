package com.coara.browser.webview;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.coara.browser.BrowserApplication;
import com.coara.browser.DownloadHistoryManager;
import com.coara.browser.DownloadHistoryActivity;
import com.coara.browser.util.PublicStorageWriter;
import com.coara.browser.util.UiThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BlobDownloadBridge {

    private static final AtomicInteger NOTIFICATION_ID_SEQ = new AtomicInteger(20000);
    private static final long NOTIFY_INTERVAL_MS = 400;

    private final Context context;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public BlobDownloadBridge(Context context) {
        this.context = context.getApplicationContext();
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
    }

    @JavascriptInterface
    public void onBlobStart(String token, String fileName, String mimeType, String totalSizeStr) {
        UiThread.io().execute(() -> {
            Session session = new Session();
            try {
                long totalSize = 0;
                try {
                    totalSize = Long.parseLong(totalSizeStr);
                } catch (NumberFormatException ignored) {
                }
                String effectiveMime = (mimeType != null && !mimeType.isEmpty())
                        ? mimeType : "application/octet-stream";
                session.target = PublicStorageWriter.openDownloadsTarget(context, fileName, effectiveMime);
                session.downloadId = System.currentTimeMillis();
                session.fileName = fileName;
                session.totalSize = totalSize;
                session.notificationId = NOTIFICATION_ID_SEQ.incrementAndGet();
                sessions.put(token, session);
                DownloadHistoryManager.updateManualDownload(context, session.downloadId, fileName,
                        session.target.displayPath, DownloadManager.STATUS_RUNNING, 0, totalSize);
                postProgressNotification(session);
            } catch (Exception e) {
                postToast("ダウンロード開始に失敗しました");
            }
        });
    }

    @JavascriptInterface
    public void onBlobChunk(String token, String base64Chunk) {
        Session session = sessions.get(token);
        if (session == null || session.failed) {
            return;
        }
        UiThread.io().execute(() -> {
            synchronized (session) {
                if (session.failed || session.target == null) {
                    return;
                }
                try {
                    byte[] data = Base64.decode(base64Chunk, Base64.DEFAULT);
                    session.target.outputStream.write(data);
                    session.bytesWritten += data.length;
                    long now = System.currentTimeMillis();
                    if (now - session.lastNotifyTime > NOTIFY_INTERVAL_MS) {
                        session.lastNotifyTime = now;
                        postProgressNotification(session);
                        DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                                session.target.displayPath, DownloadManager.STATUS_RUNNING,
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
        Session session = sessions.remove(token);
        if (session == null) {
            return;
        }
        UiThread.io().execute(() -> {
            synchronized (session) {
                if (session.failed) {
                    return;
                }
                try {
                    PublicStorageWriter.finish(context, session.target);
                    DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                            session.target.displayPath, DownloadManager.STATUS_SUCCESSFUL,
                            session.bytesWritten, session.bytesWritten);
                    postCompleteNotification(session);
                } catch (Exception e) {
                    postToast("ダウンロードの完了処理に失敗しました");
                }
            }
        });
    }

    @JavascriptInterface
    public void onBlobDownloadError(String errorMessage) {
        postToast("blob ダウンロードエラー: " + errorMessage);
    }

    @JavascriptInterface
    public void onBlobError(String token, String message) {
        Session session = sessions.remove(token);
        if (session == null) {
            postToast("ダウンロードに失敗しました");
            return;
        }
        UiThread.io().execute(() -> failSession(token, session));
    }

    private void failSession(String token, Session session) {
        synchronized (session) {
            if (session.failed) {
                return;
            }
            session.failed = true;
            sessions.remove(token);
            PublicStorageWriter.abort(context, session.target);
            DownloadHistoryManager.updateManualDownload(context, session.downloadId, session.fileName,
                    session.target != null ? session.target.displayPath : "", DownloadManager.STATUS_FAILED,
                    session.bytesWritten, session.totalSize);
            postFailedNotification(session);
        }
        postToast("ダウンロードに失敗しました");
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
                .setContentTitle(session.fileName)
                .setContentText("ダウンロード失敗")
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        showNotification(session.notificationId, builder);
    }

    private void showNotification(int id, NotificationCompat.Builder builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException ignored) {
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
