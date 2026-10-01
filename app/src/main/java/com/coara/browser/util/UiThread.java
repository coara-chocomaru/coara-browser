package com.coara.browser.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class UiThread {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = newPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()))
    );

    private UiThread() {
    }

    public static Handler mainHandler() {
        return MAIN;
    }

    public static void post(Runnable runnable) {
        MAIN.post(runnable);
    }

    public static void postDelayed(Runnable runnable, long delayMillis) {
        MAIN.postDelayed(runnable, delayMillis);
    }

    public static ExecutorService io() {
        return IO;
    }

    public static ExecutorService newPool(int threads) {
        int size = Math.max(1, threads);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size, size, 20L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
