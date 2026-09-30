package com.coara.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import com.coara.browser.model.DownloadItem;
import com.coara.browser.util.DownloadSupport;

import java.io.File;
import java.util.Locale;

public class DownloadOpenActivity extends Activity {
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";
    public static final String EXTRA_DOWNLOAD_ID = "download_id";
    public static final String EXTRA_LOCAL_URI = "local_uri";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_MIME_TYPE = "mime_type";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openTarget();
    }

    private void openTarget() {
        final int notificationId = getIntent().getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        Target target = resolveTarget();
        if (target == null || target.uri == null) {
            cancelNotification(notificationId);
            Toast.makeText(this, "存在しません…", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (!exists(target.uri)) {
            cancelNotification(notificationId);
            Toast.makeText(this, "存在しません…", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String mimeType = resolveMimeType(target.uri, target.fileName, getIntent().getStringExtra(EXTRA_MIME_TYPE));
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(target.uri, mimeType == null ? "*/*" : mimeType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if (!exists(target.uri)) {
                cancelNotification(notificationId);
                Toast.makeText(this, "存在しません…", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            Intent chooser = Intent.createChooser(viewIntent, "アプリを選択");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
            cancelNotification(notificationId);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "開けるアプリがありません", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "ファイルを開けません", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private Target resolveTarget() {
        String localUri = getIntent().getStringExtra(EXTRA_LOCAL_URI);
        String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);

        if (isUsableUri(localUri)) {
            Uri uri = Uri.parse(localUri);
            if (exists(uri)) {
                return new Target(uri, chooseFileName(fileName, uri, filePath));
            }
        }

        if (!DownloadSupport.isBlank(filePath)) {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                Uri uri = toContentUri(this, file);
                if (uri != null) {
                    return new Target(uri, chooseFileName(fileName, uri, filePath));
                }
            }
        }

        long downloadId = getIntent().getLongExtra(EXTRA_DOWNLOAD_ID, -1L);
        if (downloadId > 0L) {
            Target managerTarget = resolveFromDownloadManager(downloadId);
            if (managerTarget != null) {
                return managerTarget;
            }
            DownloadItem manual = DownloadHistoryManager.getManualDownloadItem(this, downloadId);
            if (manual != null) {
                if (!DownloadSupport.isBlank(manual.localUri) && isUsableUri(manual.localUri)) {
                    Uri uri = Uri.parse(manual.localUri);
                    if (exists(uri)) {
                        return new Target(uri, chooseFileName(manual.title, uri, manual.filePath));
                    }
                }
                if (!DownloadSupport.isBlank(manual.filePath)) {
                    File file = new File(manual.filePath);
                    if (file.exists() && file.isFile()) {
                        Uri uri = toContentUri(this, file);
                        if (uri != null) {
                            return new Target(uri, chooseFileName(manual.title, uri, manual.filePath));
                        }
                    }
                }
            }
        }
        return null;
    }

    private Target resolveFromDownloadManager(long downloadId) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) return null;
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            try (android.database.Cursor cursor = manager.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                int titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                String localUri = uriIndex >= 0 ? cursor.getString(uriIndex) : null;
                String title = titleIndex >= 0 ? cursor.getString(titleIndex) : null;
                if (isUsableUri(localUri)) {
                    Uri uri = Uri.parse(localUri);
                    if (exists(uri)) {
                        return new Target(uri, chooseFileName(title, uri, null));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Uri toContentUri(Context context, File file) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            }
            return Uri.fromFile(file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUsableUri(String value) {
        if (DownloadSupport.isBlank(value)) return false;
        try {
            Uri uri = Uri.parse(value);
            return uri.getScheme() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean exists(Uri uri) {
        if (uri == null) return false;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return !DownloadSupport.isBlank(path) && new File(path).isFile();
            }
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                try (android.content.res.AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    return descriptor != null;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String chooseFileName(String preferred, Uri uri, String filePath) {
        if (!DownloadSupport.isBlank(preferred)) return preferred;
        if (!DownloadSupport.isBlank(filePath)) {
            String name = new File(filePath).getName();
            if (!DownloadSupport.isBlank(name)) return name;
        }
        try {
            String last = uri == null ? null : uri.getLastPathSegment();
            if (!DownloadSupport.isBlank(last)) return last;
        } catch (Exception ignored) {
        }
        return "download";
    }

    private String resolveMimeType(Uri uri, String fileName, String preferredMime) {
        if (!DownloadSupport.isBlank(preferredMime)) {
            String normalized = DownloadSupport.normalizeMimeType(preferredMime, fileName);
            if (!DownloadSupport.isBlank(normalized)) return normalized;
        }
        try {
            String fromResolver = getContentResolver().getType(uri);
            if (!DownloadSupport.isBlank(fromResolver)) return fromResolver;
        } catch (Exception ignored) {
        }
        String extension = null;
        if (!DownloadSupport.isBlank(fileName)) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        if (extension == null && uri != null) {
            String path = uri.getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0 && dot < path.length() - 1) extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        if (extension == null) return "*/*";
        if ("apk".equals(extension)) return "application/vnd.android.package-archive";
        if ("zip".equals(extension) || "xapk".equals(extension) || "apks".equals(extension)) return "application/zip";
        if ("pdf".equals(extension)) return "application/pdf";
        if ("mp4".equals(extension)) return "video/mp4";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "*/*" : mime;
    }

    private void cancelNotification(int notificationId) {
        if (notificationId < 0) return;
        try {
            NotificationManagerCompat.from(this).cancel(notificationId);
        } catch (Exception ignored) {
        }
    }

    private static final class Target {
        final Uri uri;
        final String fileName;
        Target(Uri uri, String fileName) {
            this.uri = uri;
            this.fileName = fileName;
        }
    }
}
