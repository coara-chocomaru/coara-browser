package com.coara.browser.webview;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.coara.browser.util.DownloadRequestSupport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class BlobDownloadBridge {
    private final Context context;
    private final Runnable completionCallback;
    private final Object lock = new Object();
    private OutputStream outputStream;
    private File legacyFile;
    private Uri mediaUri;
    private String fileName;
    private String mimeType;
    private long bytesWritten;
    private boolean failed;

    public BlobDownloadBridge(Context context) {
        this(context, null);
    }

    public BlobDownloadBridge(Context context, Runnable completionCallback) {
        this.context = context.getApplicationContext();
        this.completionCallback = completionCallback;
    }

    @JavascriptInterface
    public void onBlobDownloadStarted(String requestedFileName, String requestedMimeType) {
        synchronized (lock) {
            closeAndDeleteCurrent();
            failed = false;
            try {
                fileName = DownloadRequestSupport.sanitizeFileName(
                        requestedFileName == null || requestedFileName.trim().isEmpty()
                                ? DownloadRequestSupport.generateTimestampFileName("blob_download_", requestedMimeType)
                                : requestedFileName);
                mimeType = DownloadRequestSupport.normalizeMimeType(requestedMimeType, fileName);
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = "application/octet-stream";
                }
                fileName = ensureUniqueExtension(fileName, mimeType);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    mediaUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (mediaUri == null) {
                        throw new IllegalStateException("Downloads storage unavailable");
                    }
                    outputStream = resolver.openOutputStream(mediaUri, "w");
                } else {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        throw new SecurityException("Storage permission is not granted");
                    }
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IllegalStateException("Downloads directory unavailable");
                    }
                    legacyFile = uniqueFile(dir, fileName);
                    fileName = legacyFile.getName();
                    outputStream = new FileOutputStream(legacyFile, false);
                }
                if (outputStream == null) {
                    throw new IllegalStateException("Download output unavailable");
                }
                bytesWritten = 0L;
                failed = false;
            } catch (Exception e) {
                failed = true;
                closeAndDeleteCurrent();
                showToast("blob ダウンロードエラー: " + safeMessage(e));
                notifyCompletion();
            }
        }
    }

    @JavascriptInterface
    public void onBlobDownloadChunk(String base64Data) {
        synchronized (lock) {
            if (failed || outputStream == null || base64Data == null || base64Data.isEmpty()) {
                return;
            }
            try {
                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                outputStream.write(data);
                bytesWritten += data.length;
            } catch (Exception e) {
                failed = true;
                closeAndDeleteCurrent();
                showToast("blob ダウンロードエラー: " + safeMessage(e));
                notifyCompletion();
            }
        }
    }

    @JavascriptInterface
    public void onBlobDownloadFinished() {
        synchronized (lock) {
            if (failed) {
                return;
            }
            try {
                if (outputStream == null) {
                    throw new IllegalStateException("Download was not started");
                }
                outputStream.flush();
                outputStream.close();
                outputStream = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(mediaUri, values, null, null);
                    showToast("blob ダウンロード完了: " + mediaUri);
                } else if (legacyFile != null) {
                    showToast("blob ダウンロード完了: " + legacyFile.getAbsolutePath());
                } else {
                    throw new IllegalStateException("Download output unavailable");
                }
                mediaUri = null;
                legacyFile = null;
                fileName = null;
                mimeType = null;
                bytesWritten = 0L;
                notifyCompletion();
            } catch (Exception e) {
                failed = true;
                closeAndDeleteCurrent();
                showToast("blob ダウンロードエラー: " + safeMessage(e));
                notifyCompletion();
            }
        }
    }

    @JavascriptInterface
    public void onBlobDownloadError(String errorMessage) {
        synchronized (lock) {
            failed = true;
            closeAndDeleteCurrent();
            showToast("blob ダウンロードエラー: " + (errorMessage == null ? "unknown error" : errorMessage));
            notifyCompletion();
        }
    }


    private void notifyCompletion() {
        if (completionCallback == null) {
            return;
        }
        try {
            completionCallback.run();
        } catch (Exception ignored) {
        }
    }

    private String ensureUniqueExtension(String value, String type) {
        String extension = extensionForMime(type);
        if (extension.isEmpty()) {
            return value;
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0 && dot < value.length() - 1) {
            return value;
        }
        return value + extension;
    }

    private String extensionForMime(String type) {
        if (type == null) return "";
        String value = type.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("pdf")) return ".pdf";
        if (value.contains("zip")) return ".zip";
        if (value.contains("gzip") || value.contains("x-gzip")) return ".gz";
        if (value.contains("rar")) return ".rar";
        if (value.contains("7z")) return ".7z";
        if (value.contains("jpeg")) return ".jpg";
        if (value.contains("png")) return ".png";
        if (value.contains("gif")) return ".gif";
        if (value.contains("webp")) return ".webp";
        if (value.contains("mp4")) return ".mp4";
        if (value.contains("webm")) return ".webm";
        if (value.contains("mpeg")) return ".mp3";
        if (value.contains("plain")) return ".txt";
        if (value.contains("json")) return ".json";
        if (value.contains("html")) return ".html";
        return "";
    }

    private File uniqueFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) {
            return target;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        while (true) {
            File candidate = new File(dir, base + " (" + index + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
            index++;
        }
    }

    private void closeAndDeleteCurrent() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Exception ignored) {
        }
        outputStream = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
            try {
                context.getContentResolver().delete(mediaUri, null, null);
            } catch (Exception ignored) {
            }
        }
        mediaUri = null;
        if (legacyFile != null) {
            try {
                legacyFile.delete();
            } catch (Exception ignored) {
            }
        }
        legacyFile = null;
        fileName = null;
        mimeType = null;
        bytesWritten = 0L;
    }

    private void showToast(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
