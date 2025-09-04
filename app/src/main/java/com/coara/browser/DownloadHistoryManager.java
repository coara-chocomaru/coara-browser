package com.coara.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DownloadHistoryManager {

    private static final String PREF_NAME = "AdvancedBrowserPrefs";
    private static final String KEY_DOWNLOAD_HISTORY = "download_history";
    private static final String CHANNEL_ID = "download_channel";
    
    public static void addDownloadHistory(Context context, long downloadId, String fileName, String filePath) {
        SharedPreferences pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(pref.getString(KEY_DOWNLOAD_HISTORY, "[]"));
            JSONObject obj = new JSONObject();
            obj.put("id", downloadId);
            obj.put("fileName", fileName);
            obj.put("filePath", filePath);
            array.put(obj);
            pref.edit().putString(KEY_DOWNLOAD_HISTORY, array.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        builder = new Notification.Builder(context, CHANNEL_ID);
    } else {
        builder = new Notification.Builder(context);
    }
    builder.setContentTitle("ダウンロード開始");
    builder.setContentText(fileName);
    builder.setSmallIcon(R.drawable.ic_download2); 
    builder.setProgress(0, 0, true); 
    notificationManager.notify((int) downloadId, builder.build());
}

    public static void monitorDownloadProgress(Context context, long downloadId, DownloadManager dm) {
    new Thread(() -> {
        long startTime = SystemClock.elapsedRealtime();
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        while (true) {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            try (Cursor cursor = dm.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int bytesDownloaded = safeGetInt(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int bytesTotal = safeGetInt(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);
                    String fileName = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));

                    if (status == DownloadManager.STATUS_RUNNING) {
                        Notification.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            builder = new Notification.Builder(context, CHANNEL_ID);
                        } else {
                            builder = new Notification.Builder(context);
                        }
                        builder.setContentTitle("ダウンロード中");
                        builder.setContentText(fileName);
                        builder.setSmallIcon(R.drawable.ic_download2);
                        if (bytesTotal > 0) {
                            int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                            builder.setProgress(100, progress, false);
                        } else {
                            builder.setProgress(0, 0, true);
                        }
                        notificationManager.notify((int) downloadId, builder.build());
                    }

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Notification.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            builder = new Notification.Builder(context, CHANNEL_ID);
                        } else {
                            builder = new Notification.Builder(context);
                        }
                        builder.setContentTitle("ダウンロード完了");
                        builder.setContentText(fileName);
                        builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
                        builder.setProgress(0, 0, false);
                        notificationManager.notify((int) downloadId, builder.build());
                        break;
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        Notification.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            builder = new Notification.Builder(context, CHANNEL_ID);
                        } else {
                            builder = new Notification.Builder(context);
                        }
                        builder.setContentTitle("ダウンロード失敗");
                        builder.setContentText(fileName);
                        builder.setSmallIcon(android.R.drawable.stat_sys_warning);
                        builder.setProgress(0, 0, false);
                        notificationManager.notify((int) downloadId, builder.build());
                        break;
                    }

                    if (bytesDownloaded > 0) {
                        startTime = SystemClock.elapsedRealtime();
                    } else if (SystemClock.elapsedRealtime() - startTime > 60000) {
                        dm.remove(downloadId);
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(() ->
                                Toast.makeText(context, "ダウンロードが進行しなかったためキャンセルしました", Toast.LENGTH_SHORT).show());
                        }
                        Notification.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            builder = new Notification.Builder(context, CHANNEL_ID);
                        } else {
                            builder = new Notification.Builder(context);
                        }
                        builder.setContentTitle("ダウンロードキャンセル");
                        builder.setContentText(fileName);
                        builder.setSmallIcon(android.R.drawable.stat_sys_warning);
                        builder.setProgress(0, 0, false);
                        notificationManager.notify((int) downloadId, builder.build());
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
    }).start();
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
