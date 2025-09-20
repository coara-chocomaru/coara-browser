package com.coara.browser;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PageBg {
    private static final String PREF_NAME = "AdvancedBrowserPrefs";
    private static final String KEY_BACKGROUND_IMAGE_URI = "background_image_uri";
    private final Context context;
    private final WebView webView;
    private final SharedPreferences pref;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String> imagePickerLauncher;
    private Uri backgroundUri;
    private Runnable onImageSelectedCallback;

    public PageBg(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadSavedBackground();
    }

    public void registerLaunchers(ActivityResultLauncher<String> permLauncher, ActivityResultLauncher<String> pickerLauncher) {
        this.permissionLauncher = permLauncher;
        this.imagePickerLauncher = pickerLauncher;
    }

    public void setOnImageSelectedCallback(Runnable callback) {
        this.onImageSelectedCallback = callback;
    }

    public void selectAndSetBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launchImagePicker();
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            } else {
                launchImagePicker();
            }
        }
    }

    private void launchImagePicker() {
        imagePickerLauncher.launch("image/*");
    }

    public void handleImageSelection(Uri uri) {
        if (uri != null) {
            backgroundUri = uri;
            saveBackgroundUri();
            applyBackgroundAsync();
            if (onImageSelectedCallback != null) {
                onImageSelectedCallback.run();
            }
        }
    }

    private void saveBackgroundUri() {
        pref.edit().putString(KEY_BACKGROUND_IMAGE_URI, backgroundUri.toString()).apply();
    }

    private void loadSavedBackground() {
        String uriStr = pref.getString(KEY_BACKGROUND_IMAGE_URI, null);
        if (uriStr != null) {
            backgroundUri = Uri.parse(uriStr);
            applyBackgroundAsync();
        }
    }

    private void applyBackgroundAsync() {
        executor.execute(() -> {
            Bitmap bitmap = loadBitmapFromUri(backgroundUri);
            if (bitmap != null) {
                mainHandler.post(() -> applyBackgroundAndTransparency(bitmap));
            }
        });
    }

    private Bitmap loadBitmapFromUri(Uri uri) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            return BitmapFactory.decodeStream(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void applyBackgroundAndTransparency(Bitmap bitmap) {
        if (webView != null) {
            webView.setBackground(new BitmapDrawable(context.getResources(), bitmap));
            injectTransparencyJs();
        }
    }

    private void injectTransparencyJs() {
        String js = "javascript:(function() { " +
                "var style = document.createElement('style'); " +
                "style.innerHTML = 'body, body * { opacity: 0.5 !important; background: transparent !important; }'; " +
                "document.head.appendChild(style); " +
                "})();";
        webView.evaluateJavascript(js, null);
    }
}
