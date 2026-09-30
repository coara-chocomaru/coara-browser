package com.coara.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.SystemClock;
import android.widget.Toast;

import com.coara.browser.util.BrowserConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DownloadHistoryManager {

    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath) {
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
                updated.put(obj);
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public static void updateManualDownload(Context context, long downloadId, String fileName, String filePath,
                                              int status, long downloadedSize, long totalSize) {
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

    private static final long PENDING_STALL_TIMEOUT_MS = 5 * 60 * 1000;
    private static final long MONITOR_MAX_DURATION_MS = 2L * 60 * 60 * 1000;

    public static void monitorDownloadProgress(Context context, long downloadId, DownloadManager dm) {
        Thread worker = new Thread(() -> {
            long lastProgressTime = SystemClock.elapsedRealtime();
            long lastBytes = -1;
            long startTime = SystemClock.elapsedRealtime();
            long pendingSince = -1;
            while (!Thread.currentThread().isInterrupted()) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        break;
                    }
                    long bytesDownloaded = safeGetLong(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        break;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = safeGetInt(cursor, DownloadManager.COLUMN_REASON);
                        postToast(context, describeFailureReason(reason));
                        break;
                    }

                    if (status == DownloadManager.STATUS_PENDING) {
                        if (pendingSince < 0) {
                            pendingSince = SystemClock.elapsedRealtime();
                        } else if (SystemClock.elapsedRealtime() - pendingSince > PENDING_STALL_TIMEOUT_MS) {
                            postToast(context, "ダウンロードを開始できないため中止しました");
                            dm.remove(downloadId);
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
                    e.printStackTrace();
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
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static int safeGetInt(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getInt(index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static long safeGetLong(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getLong(index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0L;
    }
}
