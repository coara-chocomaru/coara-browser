package com.coara.browser.util;

import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

public class DownloadCompletionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (downloadId <= 0L) {
            return;
        }
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusIndex < 0 || cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                return;
            }
            int localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            int titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
            String localUri = localUriIndex >= 0 ? cursor.getString(localUriIndex) : null;
            String title = titleIndex >= 0 ? cursor.getString(titleIndex) : null;
            String mimeType = mimeIndex >= 0 ? cursor.getString(mimeIndex) : null;
            if (DownloadFallbackManager.isActive(downloadId)) {
                return;
            }
            if (DownloadSupport.isSuspiciousDownloadedFile(context, localUri, title, mimeType)) {
                return;
            }
            DownloadFallbackManager.postCompleteNotification(context, downloadId, title);
        } catch (Exception ignored) {
        }
    }
}
