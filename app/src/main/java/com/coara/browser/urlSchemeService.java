package com.coara.browser;

import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

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

        scheme = scheme.toLowerCase();

        try {
            switch (scheme) {
                case "http":
                case "https":
                    Intent mainIntent = new Intent(this, MainActivity.class);
                    mainIntent.setAction(Intent.ACTION_VIEW);
                    mainIntent.setData(uri);
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(mainIntent);
                    break;
                case "tel":
                    Intent dialIntent = new Intent(Intent.ACTION_DIAL);
                    dialIntent.setData(uri);
                    dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(dialIntent);
                    break;
                case "mailto":
                    Intent mailIntent = new Intent(Intent.ACTION_SENDTO);
                    mailIntent.setData(uri);
                    mailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(mailIntent);
                    break;
                case "sms":
                    Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
                    smsIntent.setData(uri);
                    smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(smsIntent);
                    break;
                case "geo":
                    Intent geoIntent = new Intent(Intent.ACTION_VIEW);
                    geoIntent.setData(uri);
                    geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(geoIntent);
                    break;
                case "file":
                    Intent fileIntent = new Intent(Intent.ACTION_VIEW);
                    fileIntent.setDataAndType(uri, "*/*");
                    fileIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startActivitySafely(fileIntent);
                    break;
                case "content":
                    Intent contentIntent = new Intent(Intent.ACTION_VIEW);
                    contentIntent.setData(uri);
                    contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startActivitySafely(contentIntent);
                    break;
                case "about":
                    if ("blank".equals(uri.getSchemeSpecificPart())) {
                        Intent blankIntent = new Intent(this, MainActivity.class);
                        blankIntent.setAction(Intent.ACTION_VIEW);
                        blankIntent.setData(Uri.parse("about:blank"));
                        blankIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivitySafely(blankIntent);
                    } else {
                        Intent aboutIntent = new Intent(Intent.ACTION_VIEW, uri);
                        aboutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivitySafely(aboutIntent);
                    }
                    break;
                case "intent":
                    Intent parsedIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    parsedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PackageManager pm = getPackageManager();
                    if (parsedIntent.resolveActivity(pm) != null) {
                        startActivitySafely(parsedIntent);
                    } else {
                        String fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url");
                        if (fallbackUrl != null) {
                            Intent fallbackIntent = new Intent(this, MainActivity.class);
                            fallbackIntent.setAction(Intent.ACTION_VIEW);
                            fallbackIntent.setData(Uri.parse(fallbackUrl));
                            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivitySafely(fallbackIntent);
                        } else {
                            showToast("No handler for intent scheme");
                        }
                    }
                    break;
                case "market":
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW);
                    marketIntent.setData(uri);
                    marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(marketIntent);
                    break;
                case "fb":
                    Intent fbIntent = new Intent(Intent.ACTION_VIEW);
                    fbIntent.setData(uri);
                    fbIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(fbIntent);
                    break;
                case "instagram":
                    Intent instagramIntent = new Intent(Intent.ACTION_VIEW);
                    instagramIntent.setData(uri);
                    instagramIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(instagramIntent);
                    break;
                case "tiktok":
                    Intent tiktokIntent = new Intent(Intent.ACTION_VIEW);
                    tiktokIntent.setData(uri);
                    tiktokIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(tiktokIntent);
                    break;
                case "twitter":
                    Intent twitterIntent = new Intent(Intent.ACTION_VIEW);
                    twitterIntent.setData(uri);
                    twitterIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(twitterIntent);
                    break;
                case "whatsapp":
                    Intent whatsappIntent = new Intent(Intent.ACTION_VIEW);
                    whatsappIntent.setData(uri);
                    whatsappIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(whatsappIntent);
                    break;
                case "youtube":
                    Intent youtubeIntent = new Intent(Intent.ACTION_VIEW);
                    youtubeIntent.setData(uri);
                    youtubeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(youtubeIntent);
                    break;
                case "spotify":
                    Intent spotifyIntent = new Intent(Intent.ACTION_VIEW);
                    spotifyIntent.setData(uri);
                    spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(spotifyIntent);
                    break;
                case "bitcoin":
                    Intent bitcoinIntent = new Intent(Intent.ACTION_VIEW);
                    bitcoinIntent.setData(uri);
                    bitcoinIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(bitcoinIntent);
                    break;
                case "zxing":
                    Intent qrIntent = new Intent("com.google.zxing.client.android.SCAN");
                    qrIntent.setData(uri);
                    qrIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivitySafely(qrIntent);
                    break;
                default:
                    Intent defaultIntent = new Intent(Intent.ACTION_VIEW, uri);
                    defaultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PackageManager defaultPm = getPackageManager();
                    if (defaultIntent.resolveActivity(defaultPm) != null) {
                        startActivitySafely(defaultIntent);
                    } else {
                        showToast("No application can handle this URI: " + uri.toString());
                    }
                    break;
            }
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI syntax error: " + uri.toString(), e);
            showToast("Invalid URI syntax");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error handling URI: " + uri.toString(), e);
            showToast("Error handling URI");
        } finally {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startActivitySafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Activity not found for intent: " + intent.toString(), e);
            showToast("No application found to handle this action");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting activity: " + intent.toString(), e);
            showToast("Security error handling this action");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting activity: " + intent.toString(), e);
            showToast("Error starting activity");
        }
    }

    private void showToast(final String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
