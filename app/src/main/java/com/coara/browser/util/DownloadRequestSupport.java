package com.coara.browser.util;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebView;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

public final class DownloadRequestSupport {
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile("filename\\s*=\\s*(?:\\\"([^\\\"]+)\\\"|([^;]+))", Pattern.CASE_INSENSITIVE);
    private static final int MAX_FILENAME_LENGTH = 200;

    private DownloadRequestSupport() {
    }

    public static DownloadManager.Request buildRequest(
            Context context,
            WebView webView,
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            long contentLength,
            String description,
            String destinationDirectory) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid download URL");
        }
        Uri uri = Uri.parse(url);
        String effectiveMimeType = normalizeMimeType(mimeType, url);
        String fileName = getAccurateFileName(url, contentDisposition, effectiveMimeType);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        if (effectiveMimeType != null && !effectiveMimeType.endsWith("/*")) {
            request.setMimeType(effectiveMimeType);
        }

        String effectiveUserAgent = userAgent;
        if (effectiveUserAgent == null || effectiveUserAgent.trim().isEmpty()) {
            try {
                if (webView != null) {
                    effectiveUserAgent = webView.getSettings().getUserAgentString();
                }
            } catch (Exception ignored) {
            }
        }
        if (effectiveUserAgent != null && !effectiveUserAgent.trim().isEmpty()) {
            request.addRequestHeader("User-Agent", effectiveUserAgent);
        }

        String cookies = null;
        try {
            cookies = CookieManager.getInstance().getCookie(url);
        } catch (Exception ignored) {
        }
        if (cookies != null && !cookies.trim().isEmpty()) {
            request.addRequestHeader("Cookie", cookies);
        }

        String referer = getReferer(webView, url);
        if (referer != null && !referer.isEmpty()) {
            request.addRequestHeader("Referer", referer);
        }
        request.addRequestHeader("Accept", "*/*");

        request.setDescription(description == null || description.trim().isEmpty() ? "Downloading file..." : description);
        request.setTitle(fileName);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            request.allowScanningByMediaScanner();
        }
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setDestinationInExternalPublicDir(
                destinationDirectory == null || destinationDirectory.isEmpty()
                        ? Environment.DIRECTORY_DOWNLOADS : destinationDirectory,
                fileName);
        return request;
    }

    public static String getAccurateFileName(String url, String contentDisposition, String mimeType) {
        String fileName = parseFilenameFromContentDisposition(contentDisposition);
        if (isBlank(fileName)) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        }
        if (isBlank(fileName)) {
            fileName = "download_" + System.currentTimeMillis();
        }
        fileName = sanitizeFileName(fileName);
        return ensureExtension(fileName, mimeType, url);
    }

    public static String normalizeMimeType(String mimeType, String sourceUrl) {
        if (!isBlank(mimeType)) {
            String value = mimeType.trim().toLowerCase(Locale.ROOT);
            int separator = value.indexOf(';');
            if (separator >= 0) {
                value = value.substring(0, separator).trim();
            }
            return value;
        }
        String inferred = inferMimeTypeFromUrl(sourceUrl);
        return isBlank(inferred) ? null : inferred;
    }

    public static String sanitizeFileName(String name) {
        if (isBlank(name)) {
            return "download_" + System.currentTimeMillis();
        }
        String value = name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", "_");
        value = value.replaceAll("[. ]+$", "");
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            value = "download_" + System.currentTimeMillis();
        }
        if (value.length() > MAX_FILENAME_LENGTH) {
            int dotIndex = value.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < value.length() - 1) {
                String ext = value.substring(dotIndex);
                int baseLength = Math.max(1, MAX_FILENAME_LENGTH - ext.length());
                value = value.substring(0, Math.min(dotIndex, baseLength)) + ext;
            } else {
                value = value.substring(0, MAX_FILENAME_LENGTH);
            }
        }
        return value;
    }

    public static String buildBlobDownloadScript(String url, String fallbackName, String fallbackMimeType) {
        String safeUrl = JSONObject.quote(url == null ? "" : url);
        String safeName = JSONObject.quote(fallbackName == null ? "download" : fallbackName);
        String safeMime = JSONObject.quote(
                isBlank(fallbackMimeType) ? "application/octet-stream" : fallbackMimeType);
        return "javascript:(function(){" +
                "try{" +
                "var blobUrl=" + safeUrl + ";" +
                "var fallbackName=" + safeName + ";" +
                "var fallbackType=" + safeMime + ";" +
                "var chunkSize=262144;" +
                "var sendBytes=function(bytes,next,type){" +
                "try{" +
                "var reader=new FileReader();" +
                "reader.onload=function(){" +
                "try{" +
                "var data=reader.result||'';" +
                "var comma=data.indexOf(',');" +
                "window.BlobDownloader.onBlobDownloadChunk(comma>=0?data.substring(comma+1):data);" +
                "setTimeout(next,0);" +
                "}catch(e){window.BlobDownloader.onBlobDownloadError(String(e));}" +
                "};" +
                "reader.onerror=function(){window.BlobDownloader.onBlobDownloadError('FileReader error');};" +
                "reader.readAsDataURL(new Blob([bytes],{type:type||fallbackType}));" +
                "}catch(e){window.BlobDownloader.onBlobDownloadError(String(e));}" +
                "};" +
                "var startBlobFallback=function(blob,type){" +
                "var size=blob.size||0;" +
                "var offset=0;" +
                "var sendNext=function(){" +
                "if(offset>=size){window.BlobDownloader.onBlobDownloadFinished();return;}" +
                "var end=Math.min(size,offset+chunkSize);" +
                "sendBytes(blob.slice(offset,end),function(){offset=end;sendNext();},type);" +
                "};" +
                "sendNext();" +
                "};" +
                "fetch(blobUrl,{credentials:'include'}).then(function(response){" +
                "if(!response.ok)throw new Error('HTTP '+response.status);" +
                "var type=(response.headers&&response.headers.get('Content-Type'))||fallbackType||'application/octet-stream';" +
                "if(response.body&&response.body.getReader){" +
                "window.BlobDownloader.onBlobDownloadStarted(fallbackName,type);" +
                "var streamReader=response.body.getReader();" +
                "var pump=function(){" +
                "streamReader.read().then(function(result){" +
                "if(result.done){window.BlobDownloader.onBlobDownloadFinished();return;}" +
                "sendBytes(result.value,function(){pump();},type);" +
                "},function(error){window.BlobDownloader.onBlobDownloadError(String(error));});" +
                "};" +
                "pump();" +
                "return null;" +
                "}" +
                "return response.blob().then(function(blob){" +
                "var type=blob.type||fallbackType||'application/octet-stream';" +
                "window.BlobDownloader.onBlobDownloadStarted(fallbackName,type);" +
                "startBlobFallback(blob,type);" +
                "});" +
                "}).catch(function(error){window.BlobDownloader.onBlobDownloadError(String(error));});" +
                "}catch(error){window.BlobDownloader.onBlobDownloadError(String(error));}" +
                "})()";
    }

    public static String generateTimestampFileName(String prefix, String mimeType) {
        String normalizedPrefix = isBlank(prefix) ? "download_" : prefix;
        String name = normalizedPrefix + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new java.util.Date());
        String extension = extensionFromMime(mimeType);
        if (!extension.isEmpty()) {
            name += extension;
        }
        return sanitizeFileName(name);
    }

    private static String getReferer(WebView webView, String downloadUrl) {
        if (webView == null) {
            return null;
        }
        String pageUrl;
        try {
            pageUrl = webView.getUrl();
        } catch (Exception e) {
            return null;
        }
        if (!isHttpUrl(pageUrl) || !isHttpUrl(downloadUrl)) {
            return null;
        }
        if (pageUrl.equals(downloadUrl)) {
            return null;
        }
        try {
            Uri source = Uri.parse(pageUrl);
            Uri target = Uri.parse(downloadUrl);
            String sourceScheme = source.getScheme();
            String targetScheme = target.getScheme();
            String sourceHost = source.getHost();
            String targetHost = target.getHost();
            int sourcePort = source.getPort();
            int targetPort = target.getPort();
            boolean sameOrigin = sourceScheme != null && targetScheme != null
                    && sourceHost != null && targetHost != null
                    && sourceScheme.equalsIgnoreCase(targetScheme)
                    && sourceHost.equalsIgnoreCase(targetHost)
                    && sourcePort == targetPort;
            int fragment = pageUrl.indexOf('#');
            if (fragment >= 0) {
                pageUrl = pageUrl.substring(0, fragment);
            }
            if (sameOrigin) {
                return pageUrl;
            }
            StringBuilder origin = new StringBuilder();
            origin.append(sourceScheme).append("://").append(sourceHost);
            if (sourcePort != -1) {
                origin.append(':').append(sourcePort);
            }
            origin.append('/');
            return origin.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isHttpUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String parseFilenameFromContentDisposition(String value) {
        if (isBlank(value)) {
            return null;
        }
        Matcher star = FILENAME_STAR.matcher(value);
        if (star.find()) {
            String charset = star.group(1).trim();
            String encoded = star.group(2).trim();
            try {
                return URLDecoder.decode(encoded, isBlank(charset) ? StandardCharsets.UTF_8.name() : charset);
            } catch (Exception ignored) {
                try {
                    return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                } catch (Exception ignoredAgain) {
                    return encoded;
                }
            }
        }
        Matcher normal = FILENAME.matcher(value);
        if (normal.find()) {
            String result = normal.group(1) != null ? normal.group(1) : normal.group(2);
            if (result != null) {
                return result.trim();
            }
        }
        return null;
    }

    private static String ensureExtension(String fileName, String mimeType, String sourceUrl) {
        int dotIndex = fileName.lastIndexOf('.');
        boolean hasExtension = dotIndex > 0 && dotIndex < fileName.length() - 1;
        if (hasExtension) {
            return fileName;
        }
        String extension = extensionFromMime(mimeType);
        if (extension.isEmpty()) {
            extension = extensionFromMime(inferMimeTypeFromUrl(sourceUrl));
        }
        return extension.isEmpty() ? fileName : fileName + extension;
    }

    private static String inferMimeTypeFromUrl(String sourceUrl) {
        if (isBlank(sourceUrl)) {
            return null;
        }
        try {
            String path = Uri.parse(sourceUrl).getPath();
            if (isBlank(path)) {
                return null;
            }
            String ext = MimeTypeMap.getFileExtensionFromUrl(path);
            if (isBlank(ext)) {
                return null;
            }
            ext = ext.toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (!isBlank(mime)) {
                return mime;
            }
            if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
            if ("png".equals(ext)) return "image/png";
            if ("gif".equals(ext)) return "image/gif";
            if ("webp".equals(ext)) return "image/webp";
            if ("bmp".equals(ext)) return "image/bmp";
            if ("svg".equals(ext)) return "image/svg+xml";
            if ("pdf".equals(ext)) return "application/pdf";
            if ("zip".equals(ext)) return "application/zip";
            if ("rar".equals(ext)) return "application/vnd.rar";
            if ("7z".equals(ext)) return "application/x-7z-compressed";
            if ("gz".equals(ext)) return "application/gzip";
            if ("tar".equals(ext)) return "application/x-tar";
            if ("apk".equals(ext)) return "application/vnd.android.package-archive";
            if ("mp4".equals(ext)) return "video/mp4";
            if ("mkv".equals(ext)) return "video/x-matroska";
            if ("webm".equals(ext)) return "video/webm";
            if ("mp3".equals(ext)) return "audio/mpeg";
            if ("wav".equals(ext)) return "audio/wav";
            if ("flac".equals(ext)) return "audio/flac";
            if ("txt".equals(ext)) return "text/plain";
            if ("csv".equals(ext)) return "text/csv";
            if ("json".equals(ext)) return "application/json";
            if ("xml".equals(ext)) return "application/xml";
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String extensionFromMime(String mimeType) {
        if (isBlank(mimeType)) {
            return "";
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized.trim());
        if (!isBlank(ext)) {
            return "." + ext;
        }
        if (normalized.contains("jpeg")) return ".jpg";
        if (normalized.contains("png")) return ".png";
        if (normalized.contains("gif")) return ".gif";
        if (normalized.contains("webp")) return ".webp";
        if (normalized.contains("bmp")) return ".bmp";
        if (normalized.contains("svg")) return ".svg";
        if (normalized.contains("html")) return ".html";
        if (normalized.contains("json")) return ".json";
        if (normalized.contains("pdf")) return ".pdf";
        if (normalized.contains("zip")) return ".zip";
        if (normalized.contains("rar")) return ".rar";
        if (normalized.contains("7z")) return ".7z";
        if (normalized.contains("gzip") || normalized.contains("x-gzip")) return ".gz";
        if (normalized.contains("tar")) return ".tar";
        if (normalized.contains("apk")) return ".apk";
        if (normalized.contains("mp4")) return ".mp4";
        if (normalized.contains("matroska")) return ".mkv";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("mpeg")) return ".mp3";
        if (normalized.contains("wav")) return ".wav";
        if (normalized.contains("flac")) return ".flac";
        if (normalized.contains("text/plain")) return ".txt";
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
