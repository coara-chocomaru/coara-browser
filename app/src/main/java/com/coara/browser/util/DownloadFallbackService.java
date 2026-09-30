package com.coara.browser.util;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.coara.browser.DownloadHistoryManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadFallbackService extends Service {
    public static final String EXTRA_DOWNLOAD_ID = "download_id";
    private final Map<Long, Long> activeGenerations = new ConcurrentHashMap<>();
    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long downloadId = intent != null ? intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L) : -1L;
        if (downloadId > 0) {
            promoteToForeground(downloadId);
            launchDownload(downloadId, startId);
        } else {
            List<Long> ids = DownloadHistoryManager.getRunningManualDownloadIds(this);
            if (ids.isEmpty()) {
                stopSelfResult(startId);
            } else {
                promoteToForeground(ids.get(0));
                for (Long id : ids) {
                    launchDownload(id, startId);
                }
            }
        }
        return START_STICKY;
    }

    private void promoteToForeground(long downloadId) {
        if (downloadId <= 0) return;
        com.coara.browser.model.DownloadItem item = DownloadHistoryManager.getManualDownloadItem(this, downloadId);
        String title = item == null ? "ダウンロード" : item.title;
        long downloaded = item == null ? 0L : item.downloadedSize;
        long total = item == null ? 0L : item.totalSize;
        startForeground(DownloadFallbackManager.notificationId(downloadId),
                DownloadFallbackManager.buildProgressNotification(this, downloadId, title, downloaded, total));
    }

    private void launchDownload(long downloadId, int startId) {
        long generation = DownloadFallbackManager.ensureGeneration(this, downloadId);
        if (generation < 0L) return;
        Long runningGeneration = activeGenerations.putIfAbsent(downloadId, generation);
        if (runningGeneration != null && runningGeneration.longValue() == generation) {
            return;
        }
        if (runningGeneration != null) {
            activeGenerations.put(downloadId, generation);
        }
        executor.execute(() -> {
            try {
                DownloadFallbackManager.runPersistedDownload(getApplicationContext(), downloadId);
            } finally {
                activeGenerations.remove(downloadId, generation);
                if (activeGenerations.isEmpty()) {
                    stopSelfResult(startId);
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        activeGenerations.clear();
        stopForeground(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
