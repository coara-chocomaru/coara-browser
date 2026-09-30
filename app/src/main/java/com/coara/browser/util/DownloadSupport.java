package com.coara.browser.util;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebView;

import com.coara.browser.DownloadHistoryManager;
import android.database.Cursor;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class DownloadSupport {
    private static final int MAX_FILENAME_LENGTH = 180;

    private DownloadSupport() {
    }

    public static boolean isDataUrl(String url) {
        return url != null && url.regionMatches(true, 0, "data:", 0, 5);
    }

    public static boolean isBlobUrl(String url) {
        return url != null && url.regionMatches(true, 0, "blob:", 0, 5);
    }

    public static String normalizeMimeType(String mimeType, String sourceUrl) {
        if (!isBlank(mimeType)) {
            int semicolon = mimeType.indexOf(';');
            String normalized = (semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType)
                    .trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        String inferred = inferMimeTypeFromUrl(sourceUrl);
        return isBlank(inferred) ? "application/octet-stream" : inferred;
    }

    public static String inferMimeTypeFromUrl(String sourceUrl) {
        if (isBlank(sourceUrl)) {
            return null;
        }
        try {
            String path = Uri.parse(sourceUrl).getPath();
            if (isBlank(path)) {
                path = sourceUrl;
            }
            String ext = MimeTypeMap.getFileExtensionFromUrl(path);
            if (isBlank(ext)) {
                return null;
            }
            ext = ext.toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (!isBlank(mime)) {
                return mime.toLowerCase(Locale.ROOT);
            }
            switch (ext) {
                case "apk": return "application/vnd.android.package-archive";
                case "xapk": return "application/xapk-package-archive";
                case "apks": return "application/octet-stream";
                case "zip": return "application/zip";
                case "7z": return "application/x-7z-compressed";
                case "rar": return "application/vnd.rar";
                case "gz": return "application/gzip";
                case "tar": return "application/x-tar";
                case "pdf": return "application/pdf";
                case "json": return "application/json";
                case "xml": return "application/xml";
                case "html":
                case "htm": return "text/html";
                case "txt": return "text/plain";
                case "jpg":
                case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "gif": return "image/gif";
                case "webp": return "image/webp";
                case "svg": return "image/svg+xml";
                case "bmp": return "image/bmp";
                case "mp3": return "audio/mpeg";
                case "m4a": return "audio/mp4";
                case "mp4": return "video/mp4";
                case "webm": return "video/webm";
                default: return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean shouldPreferDirectFallback(String url, String contentDisposition, String mimeType) {
        if (isBlank(url) || !BrowserUrlRouter.isWebUrl(url)) {
            return false;
        }
        String value = url.toLowerCase(Locale.ROOT);
        String path = "";
        String query = "";
        String host = "";
        try {
            Uri uri = Uri.parse(url);
            path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }
        boolean apkPureEndpoint = path.contains("/b/apk/") || path.contains("/b/xapk/") || path.contains("/b/apks/")
                || host.endsWith(".winudf.com") || value.contains("_fn=") || value.contains("_p=");
        if (apkPureEndpoint) {
            return true;
        }
        if (!isBlank(contentDisposition)) {
            return false;
        }
        String last = null;
        try {
            last = Uri.parse(url).getLastPathSegment();
        } catch (Exception ignored) {
        }
        boolean lastHasExtension = hasRecognizedDownloadExtension(last);
        String normalizedMime = normalizeMimeType(mimeType, url);
        boolean genericMime = isBlank(mimeType) || "application/octet-stream".equals(normalizedMime);
        boolean dynamicEndpoint = query.contains("version=latest") || query.contains("download=")
                || query.contains("download_url=") || query.contains("response-content-disposition=")
                || query.contains("response_content_disposition=");
        boolean packageLike = !isBlank(last) && last.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_-]*){1,8}");
        if (!lastHasExtension && dynamicEndpoint) {
            return true;
        }
        return !lastHasExtension && genericMime && (packageLike || value.contains("download"));
    }

    public static String resolveFileName(String url, String contentDisposition, String mimeType) {
        String fileName = parseContentDispositionFileName(contentDisposition);
        if (isBlank(fileName)) {
            fileName = DownloadHintStore.consume(url);
        }
        String queryName = extractFileNameFromQuery(url);
        if (isBlank(fileName) || isWeakFileName(fileName) || isOpaqueFileName(fileName)) {
            if (!isBlank(queryName)) {
                fileName = queryName;
            }
        }
        if (isBlank(fileName)) {
            fileName = extractFileNameFromUrl(url);
        }
        if (isBlank(fileName)) {
            try {
                fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            } catch (Exception ignored) {
            }
        }
        if (isWeakFileName(fileName) || isOpaqueFileName(fileName)) {
            if (!isBlank(queryName)) {
                fileName = queryName;
            }
        }
        fileName = sanitizeFileName(fileName);
        if (isBlank(fileName) || isWeakFileName(fileName)
                || (!hasRecognizedDownloadExtension(fileName) && looksLikeApkContext(url, mimeType))) {
            String packageName = extractPackageLikeFileName(url);
            if (!isBlank(packageName) && looksLikeApkContext(url, mimeType)) {
                fileName = packageName + ".apk";
            }
        }
        if (isBlank(fileName)) {
            String host = extractHost(url);
            fileName = !isBlank(host) ? host + "_download" : "download";
        }
        fileName = ensureExtension(fileName, mimeType, url);
        fileName = truncateFileName(sanitizeFileName(fileName), MAX_FILENAME_LENGTH);
        return fileName;
    }

    public static String resolveResponseFileName(String url, String contentDisposition, String contentType,
                                                 String contentLocation, String alternateFileName) {
        String fileName = parseContentDispositionFileName(contentDisposition);
        if (isBlank(fileName)) {
            fileName = extractNameParameter(contentType);
        }
        if (isBlank(fileName)) {
            fileName = parseContentDispositionFileName(contentLocation);
            if (isBlank(fileName)) {
                fileName = extractFileNameFromUrl(contentLocation);
            }
        }
        if (isBlank(fileName)) {
            fileName = extractFileNameFromQuery(url);
        }
        if (!isBlank(alternateFileName) && (isBlank(fileName) || isWeakFileName(fileName) || isOpaqueFileName(fileName))) {
            String alternate = parseContentDispositionFileName(alternateFileName);
            if (isBlank(alternate)) alternate = alternateFileName;
            if (!isBlank(alternate) && (isBlank(fileName) || isWeakFileName(fileName) || isOpaqueFileName(fileName))) {
                fileName = alternate;
            }
        }
        if (isBlank(fileName) || isWeakFileName(fileName) || isOpaqueFileName(fileName)) {
            String resolved = resolveFileName(url, contentDisposition, contentType);
            if (!isBlank(resolved)) {
                fileName = resolved;
            }
        }
        return truncateFileName(sanitizeFileName(fileName), MAX_FILENAME_LENGTH);
    }

    public static String refineMimeType(String mimeType, String sourceUrl, byte[] sample, int sampleLength) {
        String normalized = normalizeMimeType(mimeType, sourceUrl);
        String magic = extensionFromMagic(sample, sampleLength);
        if (looksLikeApkContext(sourceUrl, normalized) && ".zip".equals(magic)) {
            return "application/vnd.android.package-archive";
        }
        if ("application/octet-stream".equals(normalized)) {
            if (".pdf".equals(magic)) return "application/pdf";
            if (".png".equals(magic)) return "image/png";
            if (".jpg".equals(magic)) return "image/jpeg";
            if (".gif".equals(magic)) return "image/gif";
            if (".gz".equals(magic)) return "application/gzip";
            if (".7z".equals(magic)) return "application/x-7z-compressed";
            if (".rar".equals(magic)) return "application/vnd.rar";
            if (".zip".equals(magic)) return "application/zip";
        }
        return normalized;
    }

    public static String refineFileName(String fileName, String sourceUrl, String mimeType, byte[] sample, int sampleLength) {
        String result = sanitizeFileName(fileName);
        String normalizedMime = refineMimeType(mimeType, sourceUrl, sample, sampleLength);
        String extension = hasRecognizedDownloadExtension(result) ? extensionOf(result) : "";
        String magicExtension = extensionFromMagic(sample, sampleLength);
        if (isBlank(result) || isWeakFileName(result) || isOpaqueFileName(result)) {
            String packageName = extractPackageLikeFileName(sourceUrl);
            if (!isBlank(packageName) && looksLikeApkContext(sourceUrl, normalizedMime)) {
                result = packageName + ".apk";
            } else if (!isBlank(magicExtension)) {
                String base = isBlank(result) ? "download" : stripExtension(result);
                result = base + magicExtension;
            }
        } else if (extension.isEmpty() && !magicExtension.isEmpty()) {
            result += magicExtension;
        }
        if (looksLikeApkContext(sourceUrl, normalizedMime) && ".zip".equals(magicExtension)
                && (isBlank(extension) || ".zip".equalsIgnoreCase(extension))) {
            result = stripExtension(result) + ".apk";
        }
        result = ensureExtension(result, normalizedMime, sourceUrl);
        if (isBlank(result)) {
            result = "download";
        }
        return truncateFileName(sanitizeFileName(result), MAX_FILENAME_LENGTH);
    }

    public static boolean looksLikeHtml(byte[] data, int length) {
        if (data == null || length <= 0) {
            return false;
        }
        int safeLength = Math.min(length, data.length);
        String sample = new String(data, 0, safeLength, StandardCharsets.UTF_8);
        if (!sample.isEmpty() && sample.charAt(0) == '\ufeff') {
            sample = sample.substring(1);
        }
        sample = sample.trim().toLowerCase(Locale.ROOT);
        return sample.startsWith("<!doctype html") || sample.startsWith("<html") || sample.startsWith("<head")
                || sample.startsWith("<body") || sample.startsWith("<title") || sample.startsWith("<script")
                || sample.startsWith("<meta") || sample.startsWith("<div") || sample.startsWith("<iframe");
    }

    public static boolean looksLikeExpectedBinary(String fileName, String mimeType, byte[] data, int length) {
        if (looksLikeHtml(data, length)) {
            String normalized = normalizeMimeType(mimeType, fileName);
            return !normalized.startsWith("text/html") && !normalized.startsWith("application/xhtml+xml");
        }
        String ext = extensionOf(fileName).toLowerCase(Locale.ROOT);
        String magic = extensionFromMagic(data, length);
        if (ext.isEmpty() || magic.isEmpty()) {
            return true;
        }
        if (ext.equals(".apk") || ext.equals(".xapk") || ext.equals(".apks") || ext.equals(".zip")) {
            return magic.equals(".zip");
        }
        if (ext.equals(".pdf")) return magic.equals(".pdf");
        if (ext.equals(".png")) return magic.equals(".png");
        if (ext.equals(".jpg") || ext.equals(".jpeg")) return magic.equals(".jpg");
        if (ext.equals(".gif")) return magic.equals(".gif");
        if (ext.equals(".gz")) return magic.equals(".gz");
        if (ext.equals(".7z")) return magic.equals(".7z");
        if (ext.equals(".rar")) return magic.equals(".rar");
        return true;
    }

    public static boolean hasRecognizedDownloadExtension(String value) {
        if (isBlank(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        String[] extensions = {
                ".apk", ".xapk", ".apks", ".zip", ".7z", ".rar", ".gz", ".bz2", ".tar", ".tgz",
                ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".jpg", ".jpeg", ".png",
                ".gif", ".webp", ".svg", ".bmp", ".mp3", ".m4a", ".mp4", ".webm", ".flac", ".wav",
                ".bin", ".exe", ".dmg", ".iso", ".msi", ".deb", ".rpm", ".jar", ".aar", ".json", ".xml",
                ".txt", ".csv", ".html", ".htm", ".css", ".js", ".mjs", ".7z.001"
        };
        for (String extension : extensions) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static String extractPackageLikeFileName(String url) {
        if (isBlank(url)) return null;
        try {
            String last = Uri.parse(url).getLastPathSegment();
            if (isBlank(last)) return null;
            String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8.name());
            if (decoded.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_-]*){1,8}")) {
                return sanitizeFileName(decoded);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksLikeApkContext(String url, String mimeType) {
        String value = isBlank(url) ? "" : url.toLowerCase(Locale.ROOT);
        String mime = isBlank(mimeType) ? "" : mimeType.toLowerCase(Locale.ROOT);
        String host = "";
        try {
            host = Uri.parse(url).getHost();
            host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }
        return mime.contains("apk") || value.contains("/b/apk/") || value.contains(".apk?") || value.contains("/apk/")
                || host.endsWith(".winudf.com") || value.contains("_fn=") || value.contains("_p=");
    }

    private static String extensionFromMagic(byte[] data, int length) {
        if (data == null || length <= 0) return "";
        int n = Math.min(length, data.length);
        if (n >= 4 && data[0] == 'P' && data[1] == 'K'
                && (((data[2] & 0xff) == 0x03 && (data[3] & 0xff) == 0x04)
                || ((data[2] & 0xff) == 0x05 && (data[3] & 0xff) == 0x06)
                || ((data[2] & 0xff) == 0x07 && (data[3] & 0xff) == 0x08))) return ".zip";
        if (n >= 5 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' && data[4] == '-') return ".pdf";
        if (n >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xff) == 0x0d && (data[5] & 0xff) == 0x0a && (data[6] & 0xff) == 0x1a && (data[7] & 0xff) == 0x0a) return ".png";
        if (n >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) return ".jpg";
        if (n >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') return ".gif";
        if (n >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) return ".gz";
        if (n >= 6 && (data[0] & 0xff) == 0x37 && (data[1] & 0xff) == 0x7a && (data[2] & 0xff) == 0xbc
                && (data[3] & 0xff) == 0xaf && (data[4] & 0xff) == 0x27 && (data[5] & 0xff) == 0x1c) return ".7z";
        if (n >= 4 && data[0] == 'R' && data[1] == 'a' && data[2] == 'r' && data[3] == '!') return ".rar";
        return "";
    }

    private static String stripExtension(String value) {
        String ext = extensionOf(value);
        return ext.isEmpty() ? value : value.substring(0, value.length() - ext.length());
    }

    public static String resolveUniqueDownloadFileName(Context context, String fileName) {
        String safe = sanitizeFileName(fileName);
        if (isBlank(safe)) {
            safe = "download";
        }
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String candidate = safe;
                for (int i = 0; i <= 9999; i++) {
                    String selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
                    try (Cursor cursor = context.getContentResolver().query(collection,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, selection,
                            new String[]{candidate}, null)) {
                        if (cursor == null || !cursor.moveToFirst()) {
                            return candidate;
                        }
                    }
                    String ext = extensionOf(safe);
                    String base = ext.isEmpty() ? safe : safe.substring(0, safe.length() - ext.length());
                    if (i == 9999) {
                        return base + "_" + System.currentTimeMillis() + ext;
                    }
                    candidate = i == 0 ? base + " (1)" + ext : base + " (" + (i + 1) + ")" + ext;
                }
            } catch (Exception ignored) {
            }
        }
        return resolveUniqueDownloadFileName(safe);
    }

    public static String buildTimestampFileName(String prefix, String mimeType) {
        String p = isBlank(prefix) ? "download_" : sanitizeFileName(prefix);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new java.util.Date());
        String ext = extensionForMime(mimeType);
        return sanitizeFileName(p + stamp) + ext;
    }

    public static String resolveUniqueDownloadFileName(String fileName) {
        String safe = sanitizeFileName(fileName);
        if (isBlank(safe)) {
            safe = "download";
        }
        java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        java.io.File candidate = new java.io.File(dir, safe);
        if (!candidate.exists()) {
            return safe;
        }
        String ext = extensionOf(safe);
        String base = ext.isEmpty() ? safe : safe.substring(0, safe.length() - ext.length());
        for (int i = 1; i <= 9999; i++) {
            String next = base + " (" + i + ")" + ext;
            if (!new java.io.File(dir, next).exists()) {
                return next;
            }
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }


    public static boolean saveDataUrl(Activity activity, String url, String fileNameHint, String mimeTypeHint) {
        if (activity == null || !isDataUrl(url)) {
            return false;
        }
        try {
            int comma = url.indexOf(',');
            if (comma < 5) {
                return false;
            }
            String metadata = url.substring(5, comma);
            String dataPart = url.substring(comma + 1);
            boolean base64 = metadata.toLowerCase(Locale.ROOT).contains(";base64");
            String declaredMime = metadata;
            int semicolon = declaredMime.indexOf(';');
            if (semicolon >= 0) {
                declaredMime = declaredMime.substring(0, semicolon);
            }
            String mime = normalizeMimeType(!isBlank(declaredMime) ? declaredMime : mimeTypeHint, null);
            byte[] data;
            if (base64) {
                data = android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT);
            } else {
                String decoded = Uri.decode(dataPart);
                data = decoded.getBytes(StandardCharsets.UTF_8);
            }
            String name = resolveFileName(fileNameHint, null, mime);
            if (isBlank(fileNameHint)) {
                name = buildTimestampFileName("download_", mime);
            }
            name = resolveUniqueDownloadFileName(activity, name);
            PublicStorageWriter.Target target = null;
            try {
                target = PublicStorageWriter.openDownloadsTarget(activity, name, mime);
                target.outputStream.write(data);
                target.outputStream.flush();
                PublicStorageWriter.finish(activity, target);
                long id = DownloadHistoryManager.nextManualDownloadId();
                DownloadHistoryManager.addDownloadHistory(activity, id, name, target.displayPath, null, null, false);
                DownloadHistoryManager.updateManualDownload(activity, id, name, target.displayPath, target.localUri,
                        DownloadManager.STATUS_SUCCESSFUL, data.length, data.length);
                return true;
            } catch (Exception e) {
                if (target != null) {
                    PublicStorageWriter.abort(activity, target);
                }
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static long enqueue(Activity activity, WebView webView, String url, String userAgent,
                               String contentDisposition, String mimeType, long contentLength,
                               boolean basicAuthEnabled, String description) {
        if (activity == null || isBlank(url) || !BrowserUrlRouter.isWebUrl(url)) {
            throw new IllegalArgumentException("invalid download url");
        }
        if (isDataUrl(url) || isBlobUrl(url)) {
            throw new IllegalArgumentException("special download url");
        }
        String effectiveMime = normalizeMimeType(mimeType, url);
        if (contentLength > 0) {
            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            long free = dir.getUsableSpace();
            if (free > 0 && contentLength > free) {
                throw new IllegalStateException("insufficient storage");
            }
        }
        String fileName = resolveFileName(url, contentDisposition, effectiveMime);
        fileName = resolveUniqueDownloadFileName(activity, fileName);
        Uri uri = Uri.parse(url);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        if (!isBlank(effectiveMime) && !"application/octet-stream".equals(effectiveMime)) {
            try {
                request.setMimeType(effectiveMime);
            } catch (Exception ignored) {
            }
        }
        addHeaderIfSafe(request, "Accept", "*/*");
        addHeaderIfSafe(request, "Accept-Encoding", "identity");
        addHeaderIfSafe(request, "User-Agent", userAgent);
        WebView referrerView = webView;
        String referer = null;
        try {
            referer = referrerView != null ? referrerView.getUrl() : null;
        } catch (Exception ignored) {
        }
        if (BrowserUrlRouter.isWebUrl(referer)) {
            addHeaderIfSafe(request, "Referer", referer);
        }
        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(url);
        } catch (Exception ignored) {
        }
        addHeaderIfSafe(request, "Cookie", cookie);
        String authorization = BasicAuthManager.getAuthorizationHeaderForUrl(url, basicAuthEnabled);
        addHeaderIfSafe(request, "Authorization", authorization);
        request.setTitle(fileName);
        request.setDescription(isBlank(description) ? "Downloading file..." : description);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            throw new IllegalStateException("download manager unavailable");
        }
        long id = dm.enqueue(request);
        String filePath = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName).getAbsolutePath();
        DownloadFallbackManager.Spec spec = new DownloadFallbackManager.Spec(
                activity, webView, url, userAgent, contentDisposition, mimeType,
                contentLength, referer, fileName, effectiveMime, basicAuthEnabled);
        try {
            DownloadHistoryManager.addDownloadHistory(activity, id, fileName, filePath, userAgent, referer, basicAuthEnabled);
        } catch (Exception ignored) {
        }
        try {
            DownloadFallbackManager.register(id, spec);
        } catch (Exception ignored) {
        }
        try {
            DownloadHistoryManager.monitorDownloadProgress(activity, id, dm);
        } catch (Exception ignored) {
        }
        return id;
    }


    public static void scheduleExternalTabClose(Activity activity, WebView webView, long downloadId,
                                                String downloadUrl, Runnable closeAction) {
        if (activity == null || webView == null || downloadId <= 0 || closeAction == null) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        final long start = System.currentTimeMillis();
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                return;
            }
            try {
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) {
                    return;
                }
                try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                            if (System.currentTimeMillis() - start < 20000L) {
                                handler.postDelayed(poll[0], 350L);
                            }
                            return;
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            String localUri = null;
                            String title = null;
                            String mimeType = null;
                            int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                            if (uriIndex >= 0) {
                                localUri = cursor.getString(uriIndex);
                            }
                            int titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                            if (titleIndex >= 0) {
                                title = cursor.getString(titleIndex);
                            }
                            int mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                            if (mimeIndex >= 0) {
                                mimeType = cursor.getString(mimeIndex);
                            }
                            if (isSuspiciousDownloadedFile(activity, localUri, title, mimeType)) {
                                if (System.currentTimeMillis() - start < 20000L) {
                                    handler.postDelayed(poll[0], 500L);
                                }
                                return;
                            }
                            UiThread.postDelayed(() -> {
                                try {
                                    if (!activity.isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed())
                                            && ExternalDownloadTabTracker.shouldCloseAfterDownload(webView, downloadUrl)) {
                                        closeAction.run();
                                    }
                                } catch (Exception ignored) {
                                }
                            }, 750L);
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            return;
                        }
                    }
                }
                int manualStatus = DownloadHistoryManager.getManualDownloadStatus(activity, downloadId);
                if (manualStatus == DownloadManager.STATUS_SUCCESSFUL) {
                    UiThread.postDelayed(() -> {
                        try {
                            if (!activity.isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed())
                                    && ExternalDownloadTabTracker.shouldCloseAfterDownload(webView, downloadUrl)) {
                                closeAction.run();
                            }
                        } catch (Exception ignored) {
                        }
                    }, 750L);
                    return;
                }
                if (manualStatus == DownloadManager.STATUS_FAILED) {
                    return;
                }
            } catch (Exception ignored) {
            }
            if (System.currentTimeMillis() - start < 20000L) {
                handler.postDelayed(poll[0], 350L);
            }
        };
        handler.postDelayed(poll[0], 350L);
    }
    public static void addHeaderIfSafe(DownloadManager.Request request, String name, String value) {
        if (request == null || isBlank(value) || !isSafeHeaderValue(value)) {
            return;
        }
        try {
            request.addRequestHeader(name, value);
        } catch (Exception ignored) {
        }
    }

    public static void addHeader(HttpURLConnection connection, String name, String value) {
        if (connection == null || isBlank(value) || !isSafeHeaderValue(value)) {
            return;
        }
        try {
            connection.setRequestProperty(name, value);
        } catch (Exception ignored) {
        }
    }


    public static boolean deleteLocalUri(Context context, String localUri) {
        if (context == null || isBlank(localUri)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(localUri);
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                return context.getContentResolver().delete(uri, null, null) > 0;
            }
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return !isBlank(path) && new java.io.File(path).delete();
            }
            java.io.File file = new java.io.File(localUri);
            return file.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isSuspiciousDownloadedFile(Context context, String localUri, String fileName, String mimeType) {
        if (context == null || isBlank(localUri)) {
            return false;
        }
        java.io.InputStream in = null;
        try {
            Uri uri = Uri.parse(localUri);
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                in = context.getContentResolver().openInputStream(uri);
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (isBlank(path)) return false;
                in = new java.io.FileInputStream(path);
            } else {
                in = new java.io.FileInputStream(new java.io.File(localUri));
            }
            if (in == null) return false;
            byte[] head = new byte[4096];
            int length = 0;
            while (length < head.length) {
                int read = in.read(head, length, head.length - length);
                if (read < 0) break;
                if (read == 0) continue;
                length += read;
            }
            return !looksLikeExpectedBinary(fileName, mimeType, head, length);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean isSafeHeaderValue(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                return false;
            }
        }
        return true;
    }

    private static String parseContentDispositionFileName(String header) {
        if (isBlank(header)) {
            return null;
        }
        try {
            String filenameStar = null;
            String filename = null;
            int start = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = 0; i <= header.length(); i++) {
                boolean end = i == header.length();
                char c = end ? ';' : header.charAt(i);
                if (c == '\\' && quoted && !escaped) {
                    escaped = true;
                    continue;
                }
                if (c == '"' && !escaped) {
                    quoted = !quoted;
                }
                escaped = false;
                if (c == ';' && !quoted) {
                    String parameter = header.substring(start, i).trim();
                    int equals = parameter.indexOf('=');
                    if (equals > 0) {
                        String name = parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                        String value = parameter.substring(equals + 1).trim();
                        if ("filename*".equals(name)) {
                            String decoded = decodeFilenameStar(value);
                            if (!isBlank(decoded)) filenameStar = decoded;
                        } else if ("filename".equals(name)) {
                            String decoded = decodeLegacyFilename(value);
                            if (!isBlank(decoded)) filename = decoded;
                        }
                    }
                    start = i + 1;
                }
            }
            return !isBlank(filenameStar) ? filenameStar : filename;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decodeFilenameStar(String value) {
        String candidate = stripQuotes(value);
        if (isBlank(candidate)) return null;
        int firstTick = candidate.indexOf('\'');
        int secondTick = firstTick < 0 ? -1 : candidate.indexOf('\'', firstTick + 1);
        if (firstTick >= 0 && secondTick > firstTick) {
            String charset = candidate.substring(0, firstTick);
            String encoded = candidate.substring(secondTick + 1);
            try {
                return decodeRfc5987(encoded, isBlank(charset) ? "UTF-8" : charset);
            } catch (Exception ignored) {
                try {
                    return decodeRfc5987(encoded, StandardCharsets.UTF_8.name());
                } catch (Exception ignored2) {
                }
            }
        }
        try {
            return decodeRfc5987(candidate, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return candidate;
        }
    }

    private static String decodeLegacyFilename(String value) {
        String candidate = unescapeQuotedString(stripQuotes(value));
        candidate = decodeEncodedWord(candidate);
        if (candidate.indexOf('%') >= 0) {
            try {
                candidate = URLDecoder.decode(candidate.replace("+", "%2B"), StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }
        }
        return candidate;
    }

    private static String extractNameParameter(String contentType) {
        if (isBlank(contentType)) return null;
        int start = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i <= contentType.length(); i++) {
            boolean end = i == contentType.length();
            char c = end ? ';' : contentType.charAt(i);
            if (c == '\\' && quoted && !escaped) {
                escaped = true;
                continue;
            }
            if (c == '"' && !escaped) quoted = !quoted;
            escaped = false;
            if (c == ';' && !quoted) {
                String parameter = contentType.substring(start, i).trim();
                int equals = parameter.indexOf('=');
                if (equals > 0 && "name".equalsIgnoreCase(parameter.substring(0, equals).trim())) {
                    return decodeLegacyFilename(parameter.substring(equals + 1).trim());
                }
                start = i + 1;
            }
        }
        return null;
    }

    private static String extractFileNameFromUrl(String url) {
        if (isBlank(url)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            String last = uri.getLastPathSegment();
            if (isBlank(last)) {
                return null;
            }
            return URLDecoder.decode(last, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractFileNameFromQuery(String url) {
        if (isBlank(url)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            String[] keys = {"_fn", "fn", "filename", "fileName", "file_name", "name", "file",
                    "download_name", "downloadName", "download", "attachment", "response-content-disposition",
                    "response_content_disposition"};
            for (String key : keys) {
                String value = uri.getQueryParameter(key);
                if (!isBlank(value)) {
                    String candidate = value.trim();
                    if ("_fn".equalsIgnoreCase(key) || "fn".equalsIgnoreCase(key)) {
                        String decoded = decodeBase64FileName(candidate);
                        if (!isBlank(decoded)) return decoded;
                    }
                    if ("1".equalsIgnoreCase(candidate) || "0".equalsIgnoreCase(candidate)
                            || "true".equalsIgnoreCase(candidate) || "false".equalsIgnoreCase(candidate)
                            || "download".equalsIgnoreCase(candidate) || "file".equalsIgnoreCase(candidate)
                            || "attachment".equalsIgnoreCase(candidate)) {
                        continue;
                    }
                    if (key.toLowerCase(Locale.ROOT).contains("disposition") && candidate.toLowerCase(Locale.ROOT).contains("filename")) {
                        String parsed = parseContentDispositionFileName(candidate);
                        if (!isBlank(parsed)) {
                            return parsed;
                        }
                    }
                    String decodedWord = decodeEncodedWord(candidate);
                    return isBlank(decodedWord) ? candidate : decodedWord;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String decodeBase64FileName(String value) {
        if (isBlank(value)) return null;
        try {
            String normalized = value.trim().replace('-', '+').replace('_', '/');
            while ((normalized.length() & 3) != 0) normalized += "=";
            byte[] decoded = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT);
            String result = new String(decoded, StandardCharsets.UTF_8);
            if (!isBlank(result) && !looksLikeEncodedBlob(result)) return result;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksLikeEncodedBlob(String value) {
        return value.indexOf('\u0000') >= 0 || value.length() > MAX_FILENAME_LENGTH * 2;
    }

    private static boolean isWeakFileName(String fileName) {
        if (isBlank(fileName)) {
            return true;
        }
        String lower = sanitizeFileName(fileName).toLowerCase(Locale.ROOT);
        return "download".equals(lower) || "file".equals(lower) || "downloadfile".equals(lower)
                || "untitled".equals(lower) || lower.startsWith("download.")
                || lower.startsWith("file.") || lower.startsWith("untitled.");
    }

    private static boolean isOpaqueFileName(String fileName) {
        if (isBlank(fileName)) return true;
        String base = fileName;
        String ext = extensionOf(base);
        if (!ext.isEmpty()) base = base.substring(0, base.length() - ext.length());
        return base.matches("(?i)[0-9a-f]{24,}")
                || base.matches("[0-9a-f]{8,}[-_][0-9a-f-]{12,}")
                || base.matches("[A-Za-z0-9_-]{32,}");
    }

    private static String extractHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return sanitizeFileName(host);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String ensureExtension(String fileName, String mimeType, String sourceUrl) {
        if (isBlank(fileName)) {
            return fileName;
        }
        String ext = extensionOf(fileName);
        String desired = extensionForMime(mimeType);
        if (desired.isEmpty()) {
            desired = extensionForMime(inferMimeTypeFromUrl(sourceUrl));
        }
        if (hasRecognizedDownloadExtension(fileName)) {
            return fileName;
        }
        if (!desired.isEmpty()) {
            return fileName + desired;
        }
        return fileName;
    }

    private static String extensionForMime(String mimeType) {
        if (isBlank(mimeType)) {
            return "";
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon);
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized);
        if (!isBlank(ext)) {
            return "." + ext.toLowerCase(Locale.ROOT);
        }
        if (normalized.contains("xapk")) return ".xapk";
        if (normalized.contains("7z")) return ".7z";
        if (normalized.contains("rar")) return ".rar";
        if (normalized.contains("gzip")) return ".gz";
        if (normalized.contains("tar")) return ".tar";
        if (normalized.contains("apk")) return ".apk";
        if (normalized.contains("pdf")) return ".pdf";
        if (normalized.contains("json")) return ".json";
        if (normalized.contains("xml")) return ".xml";
        if (normalized.contains("html")) return ".html";
        if (normalized.contains("plain")) return ".txt";
        if (normalized.contains("jpeg")) return ".jpg";
        if (normalized.contains("png")) return ".png";
        if (normalized.contains("gif")) return ".gif";
        if (normalized.contains("webp")) return ".webp";
        if (normalized.contains("svg")) return ".svg";
        if (normalized.contains("bmp")) return ".bmp";
        if (normalized.contains("mpeg")) return ".mp3";
        if (normalized.contains("mp4")) return ".mp4";
        if (normalized.contains("webm")) return ".webm";
        return "";
    }

    private static String extensionOf(String fileName) {
        if (isBlank(fileName)) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        if (dot <= slash || dot >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot);
    }

    public static String sanitizeFileName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || codePoint == 0) {
                continue;
            }
            if (codePoint == '/' || codePoint == '\\') {
                codePoint = '_';
            }
            if (Character.isWhitespace(codePoint)) {
                if (lastWasSpace) {
                    continue;
                }
                codePoint = ' ';
                lastWasSpace = true;
            } else {
                lastWasSpace = false;
            }
            out.appendCodePoint(codePoint);
        }
        String result = out.toString().trim();
        while (!result.isEmpty() && (result.endsWith(".") || result.endsWith(" "))) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.equals(".") || result.equals("..") || result.isEmpty()) {
            result = "download";
        }
        String upper = result.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "CON": case "PRN": case "AUX": case "NUL":
            case "COM1": case "COM2": case "COM3": case "COM4": case "COM5": case "COM6": case "COM7": case "COM8": case "COM9":
            case "LPT1": case "LPT2": case "LPT3": case "LPT4": case "LPT5": case "LPT6": case "LPT7": case "LPT8": case "LPT9":
                result = "_" + result;
                break;
            default:
                break;
        }
        return result;
    }

    private static String truncateFileName(String value, int maxLength) {
        if (isBlank(value) || value.length() <= maxLength) return value;
        String ext = extensionOf(value);
        int available = Math.max(1, maxLength - ext.length());
        String base = ext.isEmpty() ? value : value.substring(0, value.length() - ext.length());
        int end = 0;
        int count = 0;
        while (end < base.length() && count < available) {
            int cp = base.codePointAt(end);
            int chars = Character.charCount(cp);
            if (count + chars > available) break;
            end += chars;
            count += chars;
        }
        return sanitizeFileName(base.substring(0, end)) + ext;
    }

    private static String decodeEncodedWord(String value) {
        if (isBlank(value) || !value.startsWith("=?") || !value.endsWith("?=")) {
            return value;
        }
        try {
            int q1 = value.indexOf('?', 2);
            int q2 = q1 < 0 ? -1 : value.indexOf('?', q1 + 1);
            int end = value.lastIndexOf("?=");
            if (q1 <= 2 || q2 <= q1 || end <= q2) return value;
            String charset = value.substring(2, q1);
            String encoding = value.substring(q1 + 1, q2);
            String payload = value.substring(q2 + 1, end);
            if ("B".equalsIgnoreCase(encoding)) {
                byte[] data = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
                return new String(data, isBlank(charset) ? StandardCharsets.UTF_8.name() : charset);
            }
            if ("Q".equalsIgnoreCase(encoding)) {
                StringBuilder decoded = new StringBuilder(payload.length());
                for (int i = 0; i < payload.length(); i++) {
                    char c = payload.charAt(i);
                    if (c == '_') {
                        decoded.append(' ');
                    } else if (c == '=' && i + 2 < payload.length()) {
                        int hi = Character.digit(payload.charAt(i + 1), 16);
                        int lo = Character.digit(payload.charAt(i + 2), 16);
                        if (hi >= 0 && lo >= 0) {
                            decoded.append((char) ((hi << 4) | lo));
                            i += 2;
                        } else {
                            decoded.append(c);
                        }
                    } else {
                        decoded.append(c);
                    }
                }
                return new String(decoded.toString().getBytes(StandardCharsets.ISO_8859_1),
                        isBlank(charset) ? StandardCharsets.UTF_8.name() : charset);
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private static String unescapeQuotedString(String value) {
        if (isBlank(value)) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static String decodeRfc5987(String value, String charset) throws Exception {
        if (value == null) {
            return null;
        }
        String safe = value.replace("+", "%2B");
        return URLDecoder.decode(safe, charset);
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
