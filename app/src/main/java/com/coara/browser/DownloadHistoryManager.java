package com.coara.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.widget.Toast;

import com.coara.browser.util.BrowserConstants;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private static final ScheduledExecutorService MONITOR_EXECUTOR =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "CoaraDownloadMonitor");
                thread.setDaemon(true);
                return thread;
            });

    public static void monitorDownloadProgress(Context context, long downloadId, DownloadManager dm) {
        if (dm == null || downloadId <= 0) {
            return;
        }
        final java.util.concurrent.ScheduledFuture<?>[] futureHolder = new java.util.concurrent.ScheduledFuture<?>[1];
        Runnable task = () -> {
            boolean finish = false;
            try {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        finish = true;
                    } else {
                        int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            finish = true;
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int reason = safeGetInt(cursor, DownloadManager.COLUMN_REASON);
                            String message = reason == 0
                                    ? "ダウンロードに失敗しました"
                                    : "ダウンロードに失敗しました (" + reason + ")";
                            postToast(context, message);
                            finish = true;
                        } else {
                            safeGetLong(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                            safeGetLong(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (finish && futureHolder[0] != null) {
                futureHolder[0].cancel(false);
            }
        };
        futureHolder[0] = MONITOR_EXECUTOR.scheduleWithFixedDelay(task, 1L, 2L, TimeUnit.SECONDS);
    }

    private static long safeGetLong(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return 0L;
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
}
