package com.coara.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.SystemClock;
import android.widget.Toast;

import com.coara.browser.util.BrowserConstants;
import com.coara.browser.util.DownloadFallbackManager;
import com.coara.browser.util.DownloadSupport;
import com.coara.browser.util.UiThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

public class DownloadHistoryManager {

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath) {
        addDownloadHistory(context, downloadId, fileName, filePath, null, null, false);
    }

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath,
                                          String userAgent, String referer, boolean basicAuthEnabled) {
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
            JSONArray updated = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.optLong("id", -1L) == downloadId) {
                    JSONObject next = new JSONObject(obj.toString());
                    next.put("id", downloadId);
                    next.put("fileName", fileName);
                    next.put("filePath", filePath);
                    if (userAgent != null && !userAgent.isEmpty()) next.put("userAgent", userAgent);
                    if (referer != null && !referer.isEmpty()) next.put("referer", referer);
                    next.put("basicAuthEnabled", basicAuthEnabled);
                    updated.put(next);
                    replaced = true;
                } else {
                    updated.put(obj);
                }
            }
            if (!replaced) {
                JSONObject obj = new JSONObject();
                obj.put("id", downloadId);
                obj.put("fileName", fileName);
                obj.put("filePath", filePath);
                if (userAgent != null && !userAgent.isEmpty()) obj.put("userAgent", userAgent);
                if (referer != null && !referer.isEmpty()) obj.put("referer", referer);
                obj.put("basicAuthEnabled", basicAuthEnabled);
                updated.put(obj);
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private static final AtomicLong MANUAL_DOWNLOAD_ID =
            new AtomicLong(Math.max(1L, System.currentTimeMillis() * 1000L));

    public static long nextManualDownloadId() {
        return MANUAL_DOWNLOAD_ID.incrementAndGet();
    }

    public static int getManualDownloadStatus(Context context, long downloadId) {
        if (context == null || downloadId <= 0) {
            return -1;
        }
        try {
            SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.optLong("id", -1L) == downloadId && obj.has("manualStatus")) {
                    return obj.optInt("manualStatus", DownloadManager.STATUS_FAILED);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static void updateManualDownload(Context context, long downloadId, String fileName, String filePath,
                                              int status, long downloadedSize, long totalSize) {
        updateManualDownload(context, downloadId, fileName, filePath, null, status, downloadedSize, totalSize);
    }

    public static void updateManualDownload(Context context, long downloadId, String fileName, String filePath,
                                              String localUri, int status, long downloadedSize, long totalSize) {
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
            JSONArray updated = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.optLong("id", -1L) == downloadId) {
                    JSONObject next = new JSONObject();
                    next.put("id", downloadId);
                    next.put("fileName", fileName);
                    next.put("filePath", filePath);
                    String effectiveUri = localUri;
                    if (effectiveUri == null || effectiveUri.isEmpty()) {
                        effectiveUri = obj.optString("localUri", "");
                    }
                    if (effectiveUri != null && !effectiveUri.isEmpty()) {
                        next.put("localUri", effectiveUri);
                    }
                    next.put("manualStatus", status);
                    next.put("manualDownloaded", downloadedSize);
                    next.put("manualTotal", totalSize);
                    updated.put(next);
                    replaced = true;
                } else {
                    updated.put(obj);
                }
            }
            if (!replaced) {
                JSONObject obj = new JSONObject();
                obj.put("id", downloadId);
                obj.put("fileName", fileName);
                obj.put("filePath", filePath);
                if (localUri != null && !localUri.isEmpty()) {
                    obj.put("localUri", localUri);
                }
                obj.put("manualStatus", status);
                obj.put("manualDownloaded", downloadedSize);
                obj.put("manualTotal", totalSize);
                updated.put(obj);
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private static final long PENDING_STALL_TIMEOUT_MS = 90 * 1000;
    private static final long MONITOR_MAX_DURATION_MS = 2L * 60 * 60 * 1000;

    public static void monitorDownloadProgress(Context context, long downloadId, DownloadManager dm) {
        Thread worker = new Thread(() -> {
            long lastProgressTime = SystemClock.elapsedRealtime();
            long lastBytes = -1;
            long startTime = SystemClock.elapsedRealtime();
            long pendingSince = -1;
            int consecutiveQueryErrors = 0;
            while (!Thread.currentThread().isInterrupted()) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                try (Cursor cursor = dm.query(query)) {
                    consecutiveQueryErrors = 0;
                    if (cursor == null || !cursor.moveToFirst()) {
                        break;
                    }
                    long bytesDownloaded = safeGetLong(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        String localUri = safeGetString(cursor, DownloadManager.COLUMN_LOCAL_URI);
                        String title = safeGetString(cursor, DownloadManager.COLUMN_TITLE);
                        String mimeType = safeGetString(cursor, DownloadManager.COLUMN_MEDIA_TYPE);
                        if (DownloadSupport.isSuspiciousDownloadedFile(context, localUri, title, mimeType)) {
                            DownloadFallbackManager.onSuspiciousSuccessfulDownload(context, dm, downloadId, localUri);
                            break;
                        }
                        DownloadFallbackManager.clear(downloadId);
                        break;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = safeGetInt(cursor, DownloadManager.COLUMN_REASON);
                        if (DownloadFallbackManager.shouldFallback(reason)) {
                            DownloadFallbackManager.onDownloadManagerFailure(context, dm, downloadId, reason);
                        } else {
                            DownloadFallbackManager.clear(downloadId);
                            postToast(context, describeFailureReason(reason));
                        }
                        break;
                    }

                    if (status == DownloadManager.STATUS_PENDING) {
                        if (pendingSince < 0) {
                            pendingSince = SystemClock.elapsedRealtime();
                        } else if (SystemClock.elapsedRealtime() - pendingSince > PENDING_STALL_TIMEOUT_MS) {
                            DownloadFallbackManager.onPendingTimeout(context, dm, downloadId);
                            break;
                        }
                    } else {
                        pendingSince = -1;
                    }

                    if (bytesDownloaded != lastBytes) {
                        lastBytes = bytesDownloaded;
                        lastProgressTime = SystemClock.elapsedRealtime();
                    }

                    if (SystemClock.elapsedRealtime() - startTime > MONITOR_MAX_DURATION_MS) {
                        break;
                    }
                } catch (Exception e) {
                    consecutiveQueryErrors++;
                    if (consecutiveQueryErrors >= 8) {
                        break;
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "DownloadMonitor-" + downloadId);
        worker.setDaemon(true);
        worker.start();
    }

    private static String describeFailureReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "ダウンロードを再開できませんでした";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "保存先ストレージが見つかりません";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "同名のファイルが既に存在します";
            case DownloadManager.ERROR_FILE_ERROR:
                return "ファイルの書き込みに失敗しました";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "通信エラーによりダウンロードに失敗しました";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "空き容量が不足しています";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "リダイレクトが多すぎるためダウンロードに失敗しました";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "サーバーがダウンロードを拒否しました";
            default:
                return "ダウンロードに失敗しました";
        }
    }

    private static void postToast(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        UiThread.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    private static int safeGetInt(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getInt(index);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String safeGetString(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getString(index);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long safeGetLong(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
