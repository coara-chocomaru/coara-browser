package com.coara.browser;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import java.net.URISyntaxException;

public class urlSchemeService extends Service {
    private static final String TAG = "urlSchemeService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Uri uri = intent.getData();
        if (uri == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.setAction(Intent.ACTION_VIEW);
                mainIntent.setData(uri);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mainIntent);
            } else if ("tel".equalsIgnoreCase(scheme)) {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL);
                dialIntent.setData(uri);
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(dialIntent);
            } else if ("mailto".equalsIgnoreCase(scheme)) {
                Intent mailIntent = new Intent(Intent.ACTION_SENDTO);
                mailIntent.setData(uri);
                mailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mailIntent);
            } else if ("sms".equalsIgnoreCase(scheme)) {
                Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
                smsIntent.setData(uri);
                smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(smsIntent);
            } else if ("geo".equalsIgnoreCase(scheme)) {
                Intent geoIntent = new Intent(Intent.ACTION_VIEW);
                geoIntent.setData(uri);
                geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(geoIntent);
            } else if ("file".equalsIgnoreCase(scheme)) {
                Intent fileIntent = new Intent(Intent.ACTION_VIEW);
                fileIntent.setDataAndType(uri, "*/*");
                fileIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(fileIntent);
            } else if ("content".equalsIgnoreCase(scheme)) {
                Intent contentIntent = new Intent(Intent.ACTION_VIEW);
                contentIntent.setData(uri);
                contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(contentIntent);
            } else if ("about".equalsIgnoreCase(scheme)) {
                if ("blank".equals(uri.getSchemeSpecificPart())) {
                    Intent mainIntent = new Intent(this, MainActivity.class);
                    mainIntent.setAction(Intent.ACTION_VIEW);
                    mainIntent.setData(Uri.parse("about:blank"));
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(mainIntent);
                } else {
                    Intent defaultIntent = new Intent(Intent.ACTION_VIEW, uri);
                    defaultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(defaultIntent);
                }
            } else if ("intent".equalsIgnoreCase(scheme)) {
                Intent parsedIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                parsedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PackageManager pm = getPackageManager();
                if (parsedIntent.resolveActivity(pm) != null) {
                    startActivity(parsedIntent);
                } else {
                    String fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url");
                    if (fallbackUrl != null) {
                        Intent mainIntent = new Intent(this, MainActivity.class);
                        mainIntent.setAction(Intent.ACTION_VIEW);
                        mainIntent.setData(Uri.parse(fallbackUrl));
                        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(mainIntent);
                    }
                }
            } else {
                Intent defaultIntent = new Intent(Intent.ACTION_VIEW, uri);
                defaultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PackageManager pm = getPackageManager();
                if (defaultIntent.resolveActivity(pm) != null) {
                    startActivity(defaultIntent);
                }
            }
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI syntax error: " + uri.toString(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error handling URI: " + uri.toString(), e);
        } finally {
            stopSelf();
        }

        return START_NOT_STICKY;
    }
}
