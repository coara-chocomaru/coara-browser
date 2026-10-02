package com.coara.browser;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BrowserApplication extends Application {

    private static final String CRASH_DIR_NAME = "crash_logs";
    private static final int MAX_CRASH_LOGS = 10;
    public static final String DOWNLOAD_CHANNEL_ID = "coara_downloads";
    public static final String DOWNLOAD_COMPLETE_CHANNEL_ID = "coara_downloads_complete";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        initializePlugins();
        createDownloadNotificationChannel();
    }

    private void initializePlugins() {
        try {
            String tag = detectProcessName().endsWith(":Secret_webview") ? "S" : "M";
            com.coara.browser.plugin.PluginManager.initialize(this, tag);
        } catch (Throwable ignored) {
        }
    }

    private static String detectProcessName() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline"), "UTF-8"))) {
            String line = reader.readLine();
            if (line == null) {
                return "";
            }
            int nul = line.indexOf('\0');
            return (nul >= 0 ? line.substring(0, nul) : line).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                if (manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            DOWNLOAD_CHANNEL_ID, "ダウンロード", NotificationManager.IMPORTANCE_LOW);
                    channel.setShowBadge(false);
                    manager.createNotificationChannel(channel);
                }
                if (manager.getNotificationChannel(DOWNLOAD_COMPLETE_CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            DOWNLOAD_COMPLETE_CHANNEL_ID, "ダウンロード完了", NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setShowBadge(false);
                    manager.createNotificationChannel(channel);
                }
            }
        }
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
                
            }
            try {
                com.coara.browser.plugin.PluginManager.onAppCrash(thread, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                Runtime.getRuntime().exit(10);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        File dir = new File(getFilesDir(), CRASH_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        pruneOldLogsIfNeeded(dir);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File logFile = new File(dir, "crash_" + timestamp + ".txt");

        try (FileWriter fw = new FileWriter(logFile);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println("Time: " + new Date());
            pw.println("Thread: " + thread.getName());
            pw.println("Android SDK: " + Build.VERSION.SDK_INT + " (" + Build.MODEL + ")");
            pw.println();
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            pw.println(sw);
        } catch (Exception ignored) {
            
        }
    }

    private void pruneOldLogsIfNeeded(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_CRASH_LOGS) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = files.length - MAX_CRASH_LOGS + 1;
        for (int i = 0; i < toDelete && i < files.length; i++) {
            
            files[i].delete();
        }
    }
}
