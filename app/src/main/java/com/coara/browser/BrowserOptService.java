package com.coara.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class BrowserOptService extends Service {

    private static final String TAG = "BrowserOptService";
    private static final String CHANNEL_ID = "browser_opt_channel";
    private static final int NOTIFICATION_ID = 1;

    static {
        System.loadLibrary("browseropt");
    }

    private final Object mLock = new Object();

    private final IBrowserOpt.Stub mBinder = new IBrowserOpt.Stub() {
        @Override
        public void saveFavicon(String url, byte[] bitmapData) throws RemoteException {
            synchronized (mLock) {
                try {
                    nativeSaveFavicon(url, bitmapData);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in saveFavicon", t);
                    throw new RemoteException("Native error: " + t.getMessage());
                }
            }
        }

        @Override
        public byte[] computeMD5(String input) throws RemoteException {
            synchronized (mLock) {
                try {
                    return nativeComputeMD5(input);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in computeMD5", t);
                    throw new RemoteException("Native error: " + t.getMessage());
                }
            }
        }

        @Override
        public void saveScreenshot(byte[] bitmapData, String fileName) throws RemoteException {
            synchronized (mLock) {
                try {
                    nativeSaveScreenshot(bitmapData, fileName);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in saveScreenshot", t);
                    throw new RemoteException("Native error: " + t.getMessage());
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "せつぞぐてきない");

    
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG, "うぎゃぁぁ", ex);
            
                stopSelf();
            }
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Service bound");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "Service rebound");
        super.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (mLock) {
            try {
                nativeCleanup(); 
            } catch (Throwable t) {
                Log.e(TAG, "Error in native cleanup", t);
            }
        }
        Log.d(TAG, "Service destroyed");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Browser Optimization Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Service for native optimizations");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BrowserOpt Service")
                .setContentText("Running native optimizations")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    private native void nativeSaveFavicon(String url, byte[] bitmapData);
    private native byte[] nativeComputeMD5(String input);
    private native void nativeSaveScreenshot(byte[] bitmapData, String fileName);
    private native void nativeCleanup();
}
