package com.coara.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.os.RemoteException;

public class BrowserOptService extends Service {

    private static final String CHANNEL_ID = "browser_opt_channel";
    private static final int NOTIFICATION_ID = 1;

    static {
        System.loadLibrary("browseropt");
    }
    
    private final IBrowserOpt.Stub mBinder = new IBrowserOpt.Stub() {
    @Override
    public void saveFavicon(String url, byte[] bitmapData) throws RemoteException {
        nativeSaveFavicon(url, bitmapData);
    }

    @Override
    public byte[] computeMD5(String input) throws RemoteException {
        return nativeComputeMD5(input);
    }

    @Override
    public void saveScreenshot(byte[] bitmapData, String fileName) throws RemoteException {
        nativeSaveScreenshot(bitmapData, fileName);
    }
 };
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; 
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
}
