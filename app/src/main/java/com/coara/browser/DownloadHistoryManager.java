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
import com.coara.browser.model.DownloadItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

public class DownloadHistoryManager {
    private static final Object HISTORY_LOCK = new Object();

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath) {
        addDownloadHistory(context, downloadId, fileName, filePath, null, null, false);
    }

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath,
                                          String userAgent, String referer, boolean basicAuthEnabled) {
        addDownloadHistory(context, downloadId, fileName, filePath, userAgent, referer, basicAuthEnabled, null, null, null);
    }

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath,
                                          String userAgent, String referer, boolean basicAuthEnabled,
                                          String downloadUrl, String mimeType, String contentDisposition) {
        if (context == null || downloadId <= 0) {
            return;
        }
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
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
                    if (!DownloadSupport.isBlank(downloadUrl)) next.put("downloadUrl", downloadUrl);
                    if (!DownloadSupport.isBlank(mimeType)) next.put("mimeType", mimeType);
                    if (!DownloadSupport.isBlank(contentDisposition)) next.put("contentDisposition", contentDisposition);
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
                if (!DownloadSupport.isBlank(downloadUrl)) obj.put("downloadUrl", downloadUrl);
                if (!DownloadSupport.isBlank(mimeType)) obj.put("mimeType", mimeType);
                if (!DownloadSupport.isBlank(contentDisposition)) obj.put("contentDisposition", contentDisposition);
                updated.put(obj);
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
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
                if (obj.optLong("id", -1L) == downloadId && (obj.has("manualStatus") || obj.optBoolean("pausedByUser", false))) {
                    if (obj.optBoolean("pausedByUser", false) && !obj.has("manualStatus")) {
                        return DownloadManager.STATUS_PAUSED;
                    }
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
        if (context == null || downloadId <= 0) {
            return;
        }
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                JSONArray updated = new JSONArray();
                boolean replaced = false;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.optLong("id", -1L) == downloadId) {
                        JSONObject next = new JSONObject(obj.toString());
                        next.put("id", downloadId);
                        next.put("fileName", fileName == null ? "" : fileName);
                        next.put("filePath", filePath == null ? "" : filePath);
                        String effectiveUri = localUri;
                        if (DownloadSupport.isBlank(effectiveUri)) {
                            effectiveUri = next.optString("localUri", "");
                        }
                        if (!DownloadSupport.isBlank(effectiveUri)) {
                            next.put("localUri", effectiveUri);
                        }
                        next.put("manualStatus", status);
                        next.put("manualDownloaded", Math.max(0L, downloadedSize));
                        next.put("manualTotal", Math.max(0L, totalSize));
                        next.put("manualUpdatedAt", System.currentTimeMillis());
                        if (status == DownloadManager.STATUS_PAUSED) {
                            next.put("manualPaused", true);
                        } else if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            next.put("manualPaused", false);
                            next.put("pausedByUser", false);
                        }
                        updated.put(next);
                        replaced = true;
                    } else {
                        updated.put(obj);
                    }
                }
                if (!replaced) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", downloadId);
                    obj.put("fileName", fileName == null ? "" : fileName);
                    obj.put("filePath", filePath == null ? "" : filePath);
                    if (!DownloadSupport.isBlank(localUri)) {
                        obj.put("localUri", localUri);
                    }
                    obj.put("manualStatus", status);
                    obj.put("manualDownloaded", Math.max(0L, downloadedSize));
                    obj.put("manualTotal", Math.max(0L, totalSize));
                    obj.put("manualPaused", status == DownloadManager.STATUS_PAUSED);
                    obj.put("manualUpdatedAt", System.currentTimeMillis());
                    updated.put(obj);
                }
                pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).commit();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public static void updateManualDownloadMetadata(Context context, long downloadId, String downloadUrl,
                                                     String userAgent, String referer, String mimeType,
                                                     String contentDisposition, boolean basicAuthEnabled,
                                                     String fileName, String filePath, String localUri) {
        if (context == null || downloadId <= 0) {
            return;
        }
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                JSONArray updated = new JSONArray();
                boolean replaced = false;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.optLong("id", -1L) == downloadId) {
                        JSONObject next = new JSONObject(obj.toString());
                        if (!DownloadSupport.isBlank(downloadUrl)) next.put("downloadUrl", downloadUrl);
                        if (!DownloadSupport.isBlank(userAgent)) next.put("userAgent", userAgent);
                        if (!DownloadSupport.isBlank(referer)) next.put("referer", referer);
                        if (!DownloadSupport.isBlank(mimeType)) next.put("mimeType", mimeType);
                        if (!DownloadSupport.isBlank(contentDisposition)) next.put("contentDisposition", contentDisposition);
                        next.put("basicAuthEnabled", basicAuthEnabled);
                        if (!DownloadSupport.isBlank(fileName)) next.put("fileName", fileName);
                        if (!DownloadSupport.isBlank(filePath)) next.put("filePath", filePath);
                        if (!DownloadSupport.isBlank(localUri)) next.put("localUri", localUri);
                        updated.put(next);
                        replaced = true;
                    } else {
                        updated.put(obj);
                    }
                }
                if (replaced) {
                    pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).commit();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void setManualDownloadPaused(Context context, long downloadId, boolean paused) {
        if (context == null || downloadId <= 0) return;
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.optLong("id", -1L) == downloadId) {
                        obj.put("manualPaused", paused);
                        obj.put("manualStatus", paused ? DownloadManager.STATUS_PAUSED : DownloadManager.STATUS_RUNNING);
                        obj.put("manualUpdatedAt", System.currentTimeMillis());
                        pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, array.toString()).commit();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void setDownloadManagerPaused(Context context, long downloadId, boolean paused) {
        if (context == null || downloadId <= 0) return;
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.optLong("id", -1L) == downloadId) {
                        obj.put("pausedByUser", paused);
                        obj.put("historyUpdatedAt", System.currentTimeMillis());
                        pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, array.toString()).commit();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void reconcileManualDownloads(Context context) {
        if (context == null) return;
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                boolean changed = false;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (!obj.has("manualStatus")) continue;
                    int status = obj.optInt("manualStatus", DownloadManager.STATUS_FAILED);
                    long id = obj.optLong("id", -1L);
                    if (id <= 0 || status != DownloadManager.STATUS_RUNNING) continue;
                    long updatedAt = obj.optLong("manualUpdatedAt", 0L);
                    if (updatedAt > 0L && System.currentTimeMillis() - updatedAt < 120000L) continue;
                    if (!DownloadFallbackManager.isActive(id)) {
                        obj.put("manualStatus", DownloadManager.STATUS_PAUSED);
                        obj.put("manualPaused", true);
                        obj.put("manualUpdatedAt", System.currentTimeMillis());
                        changed = true;
                    }
                }
                if (changed) {
                    pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, array.toString()).commit();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static java.util.List<Long> getRunningManualDownloadIds(Context context) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        if (context == null) return ids;
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (!obj.has("manualStatus")) continue;
                    if (obj.optInt("manualStatus", DownloadManager.STATUS_FAILED) != DownloadManager.STATUS_RUNNING) continue;
                    long id = obj.optLong("id", -1L);
                    if (id > 0) ids.add(id);
                }
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    public static DownloadItem getManualDownloadItem(Context context, long downloadId) {
        if (context == null || downloadId <= 0) return null;
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        synchronized (HISTORY_LOCK) {
            try {
                JSONArray array = new JSONArray(pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (obj.optLong("id", -1L) == downloadId && (obj.has("manualStatus") || obj.optBoolean("pausedByUser", false))) {
                        String title = obj.optString("fileName", "download");
                        String path = obj.optString("filePath", "");
                        String localUri = obj.optString("localUri", "");
                        String url = obj.optString("downloadUrl", "");
                        int status = obj.has("manualStatus") ? obj.optInt("manualStatus", DownloadManager.STATUS_FAILED) : DownloadManager.STATUS_PAUSED;
                        long downloaded = obj.optLong("manualDownloaded", 0L);
                        long total = obj.optLong("manualTotal", 0L);
                        DownloadItem item = new DownloadItem(downloadId, title, obj.optString("description", ""), status,
                                downloaded, total, localUri, url);
                        item.filePath = path;
                        item.userAgent = obj.optString("userAgent", "");
                        item.referer = obj.optString("referer", "");
                        item.basicAuthEnabled = obj.optBoolean("basicAuthEnabled", false);
                        item.isPaused = obj.optBoolean("manualPaused", status == DownloadManager.STATUS_PAUSED) || obj.optBoolean("pausedByUser", false);
                        item.manual = obj.has("manualStatus");
                        return item;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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
                        DownloadFallbackManager.postCompleteNotification(context, downloadId, title);
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
