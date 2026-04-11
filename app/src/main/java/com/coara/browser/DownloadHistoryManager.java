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

    public static void monitorDownloadProgress(Context context, long downloadId, DownloadManager dm) {
        Thread worker = new Thread(() -> {
            long lastProgressTime = SystemClock.elapsedRealtime();
            while (!Thread.currentThread().isInterrupted()) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int bytesDownloaded = safeGetInt(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            break;
                        }
                        if (bytesDownloaded > 0) {
                            lastProgressTime = SystemClock.elapsedRealtime();
                        } else if (SystemClock.elapsedRealtime() - lastProgressTime > 60000) {
                            dm.remove(downloadId);
                            postToast(context, "ダウンロードが進行しなかったためキャンセルしました");
                            break;
                        }
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
