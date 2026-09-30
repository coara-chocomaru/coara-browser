package com.coara.browser.util;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class PublicStorageWriter {

    public static final class Target {
        public final OutputStream outputStream;
        public final Uri mediaUri;
        public final File legacyFile;
        public final String displayPath;

        Target(OutputStream outputStream, Uri mediaUri, File legacyFile, String displayPath) {
            this.outputStream = outputStream;
            this.mediaUri = mediaUri;
            this.legacyFile = legacyFile;
            this.displayPath = displayPath;
        }
    }

    private PublicStorageWriter() {
    }

    public static Target openDownloadsTarget(Context context, String fileName, String mimeType) throws IOException {
        return open(context, fileName, mimeType, Environment.DIRECTORY_DOWNLOADS, MediaStore.Downloads.EXTERNAL_CONTENT_URI);
    }

    public static Target openPicturesTarget(Context context, String fileName, String mimeType) throws IOException {
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : null;
        return open(context, fileName, mimeType, Environment.DIRECTORY_PICTURES, collection);
    }

    private static Target open(Context context, String fileName, String mimeType, String publicDir, Uri collection) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && collection != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            if (mimeType != null && !mimeType.isEmpty()) {
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            }
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                throw new IOException("insert failed");
            }
            OutputStream out = context.getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IOException("openOutputStream failed");
            }
            return new Target(out, uri, null, publicDir + "/" + fileName);
        }
        File dir = Environment.getExternalStoragePublicDirectory(publicDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, fileName);
        OutputStream out = new FileOutputStream(file);
        return new Target(out, null, file, file.getAbsolutePath());
    }

    public static void finish(Context context, Target target) {
        if (target == null) {
            return;
        }
        try {
            target.outputStream.close();
        } catch (IOException ignored) {
        }
        if (target.mediaUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                context.getContentResolver().update(target.mediaUri, values, null, null);
            } catch (Exception ignored) {
            }
        } else if (target.legacyFile != null) {
            try {
                MediaScannerConnection.scanFile(context,
                        new String[]{target.legacyFile.getAbsolutePath()}, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    public static void abort(Context context, Target target) {
        if (target == null) {
            return;
        }
        try {
            target.outputStream.close();
        } catch (IOException ignored) {
        }
        if (target.mediaUri != null) {
            try {
                context.getContentResolver().delete(target.mediaUri, null, null);
            } catch (Exception ignored) {
            }
        } else if (target.legacyFile != null) {
            try {
                target.legacyFile.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
