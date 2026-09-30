package com.coara.browser.util;

import android.net.Uri;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DownloadHintStore {
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Hint> HINTS = new LinkedHashMap<>(32, 0.75f, true);

    private DownloadHintStore() {
    }

    public static void remember(String url, String fileName) {
        String key = normalize(url);
        String safeName = DownloadSupport.sanitizeFileName(fileName);
        if (key.isEmpty() || safeName.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            purgeLocked();
            HINTS.put(key, new Hint(safeName, System.currentTimeMillis()));
            while (HINTS.size() > MAX_ENTRIES) {
                Iterator<Map.Entry<String, Hint>> iterator = HINTS.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    public static String consume(String url) {
        String key = normalize(url);
        if (key.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            purgeLocked();
            Hint hint = HINTS.remove(key);
            return hint == null ? null : hint.fileName;
        }
    }

    private static void purgeLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Hint>> iterator = HINTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Hint hint = iterator.next().getValue();
            if (now - hint.createdAt > TTL_MS) {
                iterator.remove();
            }
        }
    }

    private static String normalize(String url) {
        if (DownloadSupport.isBlank(url)) {
            return "";
        }
        try {
            Uri uri = Uri.parse(url.trim());
            return uri.buildUpon().fragment(null).build().toString();
        } catch (Exception ignored) {
            return url.trim();
        }
    }

    private static final class Hint {
        final String fileName;
        final long createdAt;
        Hint(String fileName, long createdAt) {
            this.fileName = fileName;
            this.createdAt = createdAt;
        }
    }
}
