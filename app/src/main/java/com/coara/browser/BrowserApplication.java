package com.coara.browser;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application entry point.
 *
 * This class does not change any existing runtime behaviour: it only installs a
 * defensive, best-effort crash logger on top of whatever default handler Android
 * already had. Any uncaught exception is written to a small rotating log file
 * under getFilesDir()/crash_logs/ before control is handed back to the previous
 * handler, so the process still terminates exactly as it always did (no behaviour
 * change), but the next launch can optionally surface diagnostics.
 */
public class BrowserApplication extends Application {

    private static final String CRASH_DIR_NAME = "crash_logs";
    private static final int MAX_CRASH_LOGS = 10;

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
                // Never let the logger itself interfere with the real crash handling.
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
            // Best effort only; never throw from inside a crash handler.
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
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
