package com.coara.browser.webview;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class BlobDownloadBridge {
    private final Context context;

    public BlobDownloadBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void onBlobDownloaded(String base64Data, String mimeType, String fileName) {
        try {
            int commaIndex = base64Data.indexOf(",");
            String pureBase64 = commaIndex >= 0 ? base64Data.substring(commaIndex + 1) : base64Data;
            byte[] data = Base64.decode(pureBase64, Base64.DEFAULT);
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                downloadDir.mkdirs();
            }
            File file = new File(downloadDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            Toast.makeText(context, "blob ダウンロード完了: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "blob ダウンロードエラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @JavascriptInterface
    public void onBlobDownloadError(String errorMessage) {
        Toast.makeText(context, "blob ダウンロードエラー: " + errorMessage, Toast.LENGTH_LONG).show();
    }
}
