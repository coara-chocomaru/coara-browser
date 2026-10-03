package com.coara.browser.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PluginManager {
    public static final String BRIDGE_NAME = "__coaraPluginBridge";
    public static final int MAX_PLUGINS = 32;

    private static final long ERROR_WINDOW_MS = 60_000L;
    private static final int ERROR_LIMIT = 5;
    private static final int FAIL_STREAK_LIMIT = 3;
    private static final long ACTIVE_WINDOW_MS = 15_000L;
    private static final long MARKER_MAX_AGE_MS = 10L * 60_000L;
    private static final int MAX_STORAGE_KEYS = 256;
    private static final int MAX_STORAGE_KEY_LENGTH = 128;
    private static final int MAX_STORAGE_VALUE_LENGTH = 65536;
    private static final int MAX_STORAGE_FILE_BYTES = 512 * 1024;
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int LOG_RATE_LIMIT = 60;
    private static final long LOG_RATE_WINDOW_MS = 10_000L;

    public interface Listener {
        void onPluginsChanged();

        void onPluginAutoDisabled(PluginInfo info, String reason);

        default void onPluginRuntimeChanged() {
        }
    }

    public static final class InstallOutcome {
        public final PluginInfo info;
        public final boolean updated;

        InstallOutcome(PluginInfo info, boolean updated) {
            this.info = info;
            this.updated = updated;
        }
    }

    private static volatile PluginManager instance;
    private static volatile String processTag = "M";

    private final Context appContext;
    private final File root;
    private final File indexFile;
    private final File storageDir;
    private final File activeMarker;
    private final PluginLogger logger;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<PluginInfo> plugins = new ArrayList<>();
    private final Map<String, ArrayDeque<Long>> recentErrors = new HashMap<>();
    private final Map<String, JSONObject> storageCache = new HashMap<>();
    private final Map<String, String> sourceCache = new HashMap<>();
    private final Map<String, long[]> logRate = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> lastInjectedIds = new LinkedHashSet<>();
    private final byte[] tokenBytes;
    private final String token;
    private final Map<String, XhrState> xhrs = new ConcurrentHashMap<>();
    private final Map<String, WebExtParser.Messages> i18nCache = new HashMap<>();
    private final ExecutorService xhrPool = Executors.newFixedThreadPool(3);
    private final Runnable clearMarkerRunnable = new Runnable() {
        @Override
        public void run() {
            clearActiveMarker();
        }
    };

    private boolean loaded = false;
    private long indexStamp = -1L;
    private int version = 0;
    private int runtimeVersion = 0;
    private long lastInjectionAt = 0L;
    private long lastMarkerWrite = 0L;

    private PluginManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.root = new File(appContext.getFilesDir(), "plugins");
        this.indexFile = new File(root, "index.json");
        this.storageDir = new File(root, "_storage");
        this.activeMarker = new File(root, ".active_" + processTag);
        if (!root.exists()) {
            root.mkdirs();
        }
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        this.logger = new PluginLogger(new File(root, "_logs"), processTag);
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        this.tokenBytes = toHex(raw).getBytes(StandardCharsets.US_ASCII);
        this.token = new String(tokenBytes, StandardCharsets.US_ASCII);
    }

    private static final class XhrState {
        final PluginHttp.Call call = new PluginHttp.Call();
        final long startedAt = System.currentTimeMillis();
        volatile String result;
        volatile long doneAt;
    }

    public static PluginManager get(Context context) {
        PluginManager m = instance;
        if (m == null) {
            synchronized (PluginManager.class) {
                m = instance;
                if (m == null) {
                    m = new PluginManager(context);
                    instance = m;
                }
            }
        }
        return m;
    }

    public static void initialize(Context context, String tag) {
        processTag = tag == null || tag.isEmpty() ? "M" : tag;
        PluginManager m = get(context);
        try {
            m.consumeActiveMarker();
        } catch (Throwable ignored) {
        }
        try {
            m.cleanupOrphans();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isSecretProcess() {
        return "S".equals(processTag);
    }

    public static void onAppCrash(Thread thread, Throwable throwable) {
        PluginManager m = instance;
        if (m == null) {
            return;
        }
        try {
            m.handleAppCrash(thread, throwable);
        } catch (Throwable ignored) {
        }
    }

    public Object createBridge(PluginViewOps ops) {
        return new PluginBridge(this, ops);
    }

    public String getToken() {
        return token;
    }

    public String keyFor(String id) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((token + ":" + id).getBytes(StandardCharsets.UTF_8));
            return toHex(d).substring(0, 12);
        } catch (Exception e) {
            return Integer.toHexString((token + id).hashCode());
        }
    }

    public Context context() {
        return appContext;
    }

    public File pluginDir(String id) {
        return new File(root, id);
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.addIfAbsent(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized int version() {
        ensureLoadedLocked();
        return version;
    }

    public synchronized int runtimeVersion() {
        ensureLoadedLocked();
        return runtimeVersion;
    }

    public synchronized boolean reloadIfChanged() {
        if (!loaded) {
            ensureLoadedLocked();
            return true;
        }
        if (stamp() != indexStamp) {
            loadLocked();
            version++;
            runtimeVersion++;
            sourceCache.clear();
            i18nCache.clear();
            notifyChanged();
            notifyRuntimeChanged();
            return true;
        }
        return false;
    }

    public synchronized List<PluginInfo> list() {
        ensureLoadedLocked();
        List<PluginInfo> out = new ArrayList<>();
        for (PluginInfo p : plugins) {
            out.add(p.copy());
        }
        return out;
    }

    public synchronized PluginInfo get(String id) {
        ensureLoadedLocked();
        PluginInfo p = find(id);
        return p == null ? null : p.copy();
    }

    public synchronized List<PluginInfo> getEnabledPlugins() {
        ensureLoadedLocked();
        List<PluginInfo> out = new ArrayList<>();
        for (PluginInfo p : plugins) {
            if (p.enabled) {
                out.add(p.copy());
            }
        }
        return out;
    }

    public synchronized List<String> matchingEnabledIds(String url) {
        ensureLoadedLocked();
        List<String> ids = new ArrayList<>();
        for (PluginInfo p : plugins) {
            if (p.enabled && PluginPatterns.isTarget(p, url)) {
                ids.add(p.id);
            }
        }
        return ids;
    }

    public synchronized InstallOutcome install(ParsedPlugin parsed) throws PluginInstallException {
        ensureLoadedLocked();
        reloadIfChanged();
        PluginInfo info = parsed.info.copy();
        PluginInfo existing = find(info.id);
        if (existing != null && parsed.fallbackTitle) {
            info.id = uniqueId(info.id);
            existing = null;
        }
        if (existing == null && plugins.size() >= MAX_PLUGINS) {
            throw new PluginInstallException("拡張機能の数が上限(" + MAX_PLUGINS + ")に達しています");
        }
        File tmp = new File(root, ".tmp_" + System.nanoTime());
        if (!tmp.mkdirs()) {
            throw new PluginInstallException("作業ディレクトリを作成できません");
        }
        try {
            String tmpCanonical = tmp.getCanonicalPath();
            for (Map.Entry<String, byte[]> e : parsed.files.entrySet()) {
                File out = new File(tmp, e.getKey());
                if (!out.getCanonicalPath().startsWith(tmpCanonical + File.separator)) {
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("mkdir failed");
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(e.getValue());
                }
            }
            File dest = new File(root, info.id);
            if (dest.exists()) {
                deleteRecursive(dest);
            }
            if (!tmp.renameTo(dest)) {
                throw new PluginInstallException("プラグインを保存できません");
            }
        } catch (IOException e) {
            deleteRecursive(tmp);
            throw new PluginInstallException("プラグインを保存できません: " + e.getMessage());
        } catch (PluginInstallException e) {
            deleteRecursive(tmp);
            throw e;
        }
        info.installedAt = System.currentTimeMillis();
        info.enabled = true;
        info.errorCount = 0;
        info.crashCount = 0;
        info.failStreak = 0;
        info.lastError = "";
        info.lastErrorAt = 0L;
        info.disabledReason = "";
        info.disabledAt = 0L;
        boolean updated = existing != null;
        if (updated) {
            plugins.set(plugins.indexOf(existing), info);
        } else {
            plugins.add(info);
        }
        recentErrors.remove(info.id);
        sourceCache.clear();
        i18nCache.remove(info.id);
        saveLocked();
        logger.log("I", info.id, (updated ? "更新" : "インストール") + ": " + info.title
                + (info.version.isEmpty() ? "" : " v" + info.version)
                + " (" + info.format + ", entries=" + info.entries().size() + ", js=" + info.scripts.size()
                + ", css=" + info.styles.size() + ", perms=" + info.permissions + ")");
        for (String w : parsed.warnings) {
            logger.log("W", info.id, w);
        }
        notifyChanged();
        notifyRuntimeChanged();
        return new InstallOutcome(info.copy(), updated);
    }

    public synchronized boolean setEnabled(String id, boolean enabled) {
        ensureLoadedLocked();
        reloadIfChanged();
        PluginInfo p = find(id);
        if (p == null) {
            return false;
        }
        if (p.enabled == enabled) {
            return true;
        }
        p.enabled = enabled;
        if (enabled) {
            p.disabledReason = "";
            p.disabledAt = 0L;
            p.failStreak = 0;
            recentErrors.remove(id);
            logger.log("I", id, "有効化しました");
        } else {
            p.disabledReason = "手動で無効化";
            p.disabledAt = System.currentTimeMillis();
            logger.log("I", id, "無効化しました");
        }
        saveLocked();
        notifyChanged();
        notifyRuntimeChanged();
        return true;
    }

    public synchronized boolean delete(String id) {
        ensureLoadedLocked();
        reloadIfChanged();
        PluginInfo p = find(id);
        if (p == null) {
            return false;
        }
        plugins.remove(p);
        deleteRecursive(new File(root, id));
        deleteStorageFiles(id);
        storageCache.remove(id);
        recentErrors.remove(id);
        i18nCache.remove(id);
        logRate.remove(id);
        sourceCache.clear();
        logger.removeFor(id);
        saveLocked();
        notifyChanged();
        notifyRuntimeChanged();
        return true;
    }

    public synchronized String readText(PluginInfo p, String name) {
        if (p == null || name == null) {
            return null;
        }
        if (!p.resources.contains(name) && !p.scripts.contains(name) && !p.styles.contains(name)) {
            return null;
        }
        File dir = new File(root, p.id);
        File f = new File(dir, name);
        try {
            String canonicalDir = dir.getCanonicalPath();
            String canonicalFile = f.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalDir + File.separator)) {
                return null;
            }
            if (!f.isFile() || f.length() > MAX_READ_BYTES) {
                return null;
            }
            String key = p.id + "/" + name + "/" + f.lastModified() + "/" + f.length();
            String cached = sourceCache.get(key);
            if (cached != null) {
                return cached;
            }
            String text = readFileText(f);
            if (sourceCache.size() > 128) {
                sourceCache.clear();
            }
            sourceCache.put(key, text);
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized File iconFile(PluginInfo p) {
        if (p == null || p.icon == null || p.icon.isEmpty()) {
            return null;
        }
        File f = new File(new File(root, p.id), p.icon);
        return f.isFile() ? f : null;
    }

    public void log(String level, String id, String message) {
        logger.log(level, id, message);
    }

    public String readLog(int maxChars) {
        return logger.readTail(maxChars);
    }

    public String readLogFor(String id, int maxChars) {
        return logger.readFor(id, maxChars);
    }

    public void clearLog() {
        logger.clear();
    }

    public void exportLog(OutputStream out) throws IOException {
        logger.exportTo(out);
    }

    public synchronized void recordSuccess(String id) {
        PluginInfo p = find(id);
        if (p != null && p.failStreak != 0) {
            p.failStreak = 0;
            saveLocked();
        }
    }

    public synchronized void recordError(String id, String kind, String message, String stack, String url) {
        ensureLoadedLocked();
        PluginInfo p = find(id);
        if (p == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String shownUrl = isSecretProcess() ? "(secret)" : (url == null ? "" : url);
        StringBuilder detail = new StringBuilder();
        detail.append(kind).append(": ").append(message == null ? "" : message);
        if (!shownUrl.isEmpty()) {
            detail.append("\nurl=").append(shownUrl);
        }
        if (stack != null && !stack.isEmpty()) {
            detail.append('\n').append(stack.length() > 1500 ? stack.substring(0, 1500) : stack);
        }
        logger.log("E", id, detail.toString());

        p.errorCount++;
        p.lastErrorAt = now;
        String shortMsg = kind + ": " + (message == null ? "" : message);
        p.lastError = shortMsg.length() > 200 ? shortMsg.substring(0, 200) : shortMsg;
        if ("load".equals(kind)) {
            p.failStreak++;
        }

        ArrayDeque<Long> deque = recentErrors.get(id);
        if (deque == null) {
            deque = new ArrayDeque<>();
            recentErrors.put(id, deque);
        }
        deque.addLast(now);
        while (!deque.isEmpty() && now - deque.peekFirst() > ERROR_WINDOW_MS) {
            deque.pollFirst();
        }

        String disableReason = null;
        if (p.enabled) {
            if (deque.size() >= ERROR_LIMIT) {
                disableReason = "エラー多発(" + (ERROR_WINDOW_MS / 1000) + "秒間に" + ERROR_LIMIT + "回)";
            } else if (p.failStreak >= FAIL_STREAK_LIMIT) {
                disableReason = "読み込み失敗が" + FAIL_STREAK_LIMIT + "回連続(構文エラーの可能性)";
            }
        }
        if (disableReason != null) {
            disableLocked(p, disableReason);
            runtimeVersion++;
        }
        saveLocked();
        notifyChanged();
        if (disableReason != null) {
            notifyRuntimeChanged();
        }
    }

    public synchronized void recordCrash(Collection<String> ids, String reason) {
        ensureLoadedLocked();
        boolean changed = false;
        boolean runtimeChanged = false;
        for (String id : ids) {
            PluginInfo p = find(id);
            if (p == null) {
                continue;
            }
            p.crashCount++;
            p.lastErrorAt = System.currentTimeMillis();
            p.lastError = "crash: " + reason;
            logger.log("E", id, "クラッシュ検知: " + reason);
            if (p.enabled) {
                disableLocked(p, "クラッシュ検知: " + reason);
                runtimeChanged = true;
            }
            changed = true;
        }
        if (changed) {
            saveLocked();
            notifyChanged();
            if (runtimeChanged) {
                runtimeVersion++;
                notifyRuntimeChanged();
            }
        }
    }

    public synchronized void markInjected(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean same = lastInjectedIds.size() == ids.size() && lastInjectedIds.containsAll(ids)
                && now - lastMarkerWrite < 5000L;
        lastInjectedIds.clear();
        lastInjectedIds.addAll(ids);
        lastInjectionAt = now;
        if (!same) {
            writeMarker(now);
        }
        main.removeCallbacks(clearMarkerRunnable);
        main.postDelayed(clearMarkerRunnable, ACTIVE_WINDOW_MS);
    }

    public void onHostPause() {
        main.removeCallbacks(clearMarkerRunnable);
        clearActiveMarker();
    }

    String handleBridge(PluginViewOps ops, String id, String op, String a, String b, String c) {
        PluginInfo p;
        synchronized (this) {
            ensureLoadedLocked();
            p = find(id);
            if (p == null || !p.enabled) {
                return null;
            }
        }
        switch (op) {
            case "log":
                if (isSecretProcess() && !"warn".equals(a) && !"error".equals(a)) {
                    return "1";
                }
                if (allowLog(id)) {
                    String level = "warn".equals(a) ? "W" : ("error".equals(a) ? "E" : "I");
                    logger.log(level, id, "console: " + (b == null ? "" : b));
                }
                return "1";
            case "ok":
                recordSuccess(id);
                return "1";
            case "report":
                handleReport(id, a, b, c);
                return "1";
            case "sget":
                return storageGet(id, a);
            case "sset":
                return storageSet(id, a, b) ? "1" : "0";
            case "sdel":
                return storageRemove(id, a) ? "1" : "0";
            case "skeys":
                return storageKeys(id);
            case "sclear":
                return storageClear(id) ? "1" : "0";
            case "res":
                return readText(p, resolveName(p, a));
            case "resurl":
                return resourceDataUrl(p, a);
            case "i18n":
                return i18nMessage(p, a, b);
            case "uilang":
                return Locale.getDefault().toString();
            case "cmd":
                if (ops == null || a == null || a.isEmpty() || a.length() > 64) {
                    return "0";
                }
                ops.registerCommand(id, a, b == null ? a : (b.length() > 60 ? b.substring(0, 60) : b));
                return "1";
            case "uncmd":
                if (ops != null && a != null) {
                    ops.unregisterCommand(id, a);
                }
                return "1";
            case "xhr":
                if (!p.hasPermission(PluginInfo.PERM_XHR)) {
                    logger.log("W", id, "権限「xhr」が無いためGM_xmlhttpRequestを拒否しました");
                    return "0";
                }
                return xhrStart(p, a, b) ? "1" : "0";
            case "xhrPoll":
                return xhrPoll(id, a);
            case "xhrAbort":
                xhrAbort(id, a);
                return "1";
            case "open": {
                if (!p.hasPermission(PluginInfo.PERM_OPENTAB) || ops == null || !isHttpUrl(a)) {
                    return "0";
                }
                ops.openTab(a);
                return "1";
            }
            case "notify":
                if (!p.hasPermission(PluginInfo.PERM_NOTIFICATION) || ops == null || !allowLog(id + "#n")) {
                    return "0";
                }
                ops.notifyUser(a == null ? p.title : a, b == null ? "" : b);
                return "1";
            case "clip":
                if (!p.hasPermission(PluginInfo.PERM_CLIPBOARD) || ops == null || a == null || a.length() > 200000) {
                    return "0";
                }
                return ops.copyText(a) ? "1" : "0";
            default:
                return null;
        }
    }

    private static boolean isHttpUrl(String u) {
        if (u == null) {
            return false;
        }
        String l = u.toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private String resolveName(PluginInfo p, String name) {
        if (name == null) {
            return null;
        }
        String mapped = p.resourceMap.get(name);
        if (mapped != null) {
            return mapped;
        }
        String ref = PluginInstaller.refPath(name);
        return ref == null ? name : ref;
    }

    private boolean xhrStart(PluginInfo p, String reqId, String optsJson) {
        if (reqId == null || reqId.isEmpty() || reqId.length() > 40 || optsJson == null) {
            return false;
        }
        final JSONObject opts;
        try {
            opts = new JSONObject(optsJson);
        } catch (JSONException e) {
            return false;
        }
        final String key = p.id + ":" + reqId;
        long now = System.currentTimeMillis();
        int active = 0;
        for (Map.Entry<String, XhrState> e : xhrs.entrySet()) {
            XhrState s = e.getValue();
            if (s.result != null && now - s.doneAt > 120_000L) {
                xhrs.remove(e.getKey());
            } else if (s.result == null && e.getKey().startsWith(p.id + ":")) {
                active++;
            }
        }
        if (active >= 8 || xhrs.containsKey(key) || xhrs.size() > 64) {
            return false;
        }
        final XhrState state = new XhrState();
        xhrs.put(key, state);
        final List<String> connects = new ArrayList<>(p.connects);
        final String pid = p.id;
        final String shownUrl = isSecretProcess() ? "(secret)" : opts.optString("url", "");
        xhrPool.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject result = PluginHttp.execute(opts, connects, state.call);
                if (result.has("error") && !result.optBoolean("aborted", false)) {
                    logger.log("W", pid, "xhr失敗: " + result.optString("error") + " url=" + shownUrl);
                }
                state.result = result.toString();
                state.doneAt = System.currentTimeMillis();
            }
        });
        return true;
    }

    private String xhrPoll(String id, String reqId) {
        if (reqId == null) {
            return null;
        }
        String key = id + ":" + reqId;
        XhrState s = xhrs.get(key);
        if (s == null) {
            return null;
        }
        String r = s.result;
        if (r == null) {
            return "";
        }
        xhrs.remove(key);
        return r;
    }

    private void xhrAbort(String id, String reqId) {
        if (reqId == null) {
            return;
        }
        XhrState s = xhrs.get(id + ":" + reqId);
        if (s != null) {
            s.call.abort();
        }
    }

    public synchronized byte[] readBytes(PluginInfo p, String name, int maxBytes) {
        if (p == null || name == null) {
            return null;
        }
        if (!p.resources.contains(name) && !p.assets.contains(name) && !p.scripts.contains(name)
                && !p.styles.contains(name) && !name.equals(p.icon)) {
            return null;
        }
        File dir = new File(root, p.id);
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return null;
            }
            if (!f.isFile() || f.length() > maxBytes) {
                return null;
            }
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String resourceDataUrl(PluginInfo p, String name) {
        String path = resolveName(p, name);
        byte[] data = readBytes(p, path, 2 * 1024 * 1024);
        if (data == null) {
            return null;
        }
        return "data:" + mimeOf(path) + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String mimeOf(String path) {
        String ext = PluginInstaller.extOf(path == null ? "" : path);
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";
            case "bmp":
                return "image/bmp";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "css":
                return "text/css";
            case "js":
            case "mjs":
                return "text/javascript";
            case "json":
                return "application/json";
            case "html":
            case "htm":
                return "text/html";
            case "txt":
            case "md":
            case "csv":
                return "text/plain";
            case "xml":
                return "text/xml";
            default:
                return "application/octet-stream";
        }
    }

    private String i18nMessage(PluginInfo p, String key, String subsJson) {
        WebExtParser.Messages m;
        synchronized (this) {
            m = i18nCache.get(p.id);
            if (m == null) {
                m = loadMessages(p);
                i18nCache.put(p.id, m);
            }
        }
        String[] subs = null;
        if (subsJson != null && !subsJson.isEmpty()) {
            try {
                JSONArray a = new JSONArray(subsJson);
                subs = new String[a.length()];
                for (int i = 0; i < a.length(); i++) {
                    subs[i] = a.isNull(i) ? "" : a.optString(i, "");
                }
            } catch (JSONException ignored) {
            }
        }
        String v = m.lookup(key, subs);
        return v == null ? "" : v;
    }

    private WebExtParser.Messages loadMessages(PluginInfo p) {
        File locales = new File(new File(root, p.id), "_locales");
        File[] dirs = locales.listFiles();
        java.util.Set<String> available = new java.util.HashSet<>();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && new File(d, "messages.json").isFile()) {
                    available.add(d.getName());
                }
            }
        }
        String chosen = WebExtParser.pickLocale(available, p.defaultLocale);
        if (chosen == null) {
            return WebExtParser.Messages.of("{}", p.id);
        }
        try {
            File f = new File(new File(locales, chosen), "messages.json");
            if (f.length() > MAX_READ_BYTES) {
                return WebExtParser.Messages.of("{}", p.id);
            }
            return WebExtParser.Messages.of(readFileText(f), p.id);
        } catch (Exception e) {
            return WebExtParser.Messages.of("{}", p.id);
        }
    }

    public synchronized String storageKeys(String id) {
        JSONObject o = loadStorage(id);
        JSONArray a = new JSONArray();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            a.put(it.next());
        }
        return a.toString();
    }

    public synchronized boolean storageClear(String id) {
        JSONObject o = new JSONObject();
        storageCache.put(id, o);
        return saveStorage(id, o);
    }

    public synchronized int storageCount(String id) {
        return loadStorage(id).length();
    }

    public synchronized void clearData(String id) {
        storageClear(id);
        File[] files = storageDir.listFiles();
        if (files != null) {
            String prefix = id + ".";
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith(prefix) && n.endsWith(".json") && n.indexOf('.', prefix.length()) == n.length() - 5) {
                    f.delete();
                }
            }
        }
        storageCache.remove(id);
        logger.log("I", id, "保存データを消去しました");
        notifyChanged();
    }

    public synchronized void exportPlugin(String id, OutputStream out) throws IOException {
        PluginInfo p = find(id);
        if (p == null) {
            throw new IOException("not found");
        }
        File dir = new File(root, id);
        ZipOutputStream zos = new ZipOutputStream(out);
        zipDir(zos, dir, "");
        zos.finish();
        zos.flush();
    }

    private static void zipDir(ZipOutputStream zos, File dir, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File c : children) {
            String name = prefix + c.getName();
            if (c.isDirectory()) {
                zipDir(zos, c, name + "/");
            } else {
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(c)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public synchronized void cleanupOrphans() {
        if (isSecretProcess()) {
            return;
        }
        ensureLoadedLocked();
        File[] dirs = root.listFiles();
        long now = System.currentTimeMillis();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.getName().startsWith(".tmp_") && now - d.lastModified() > 60L * 60_000L) {
                    deleteRecursive(d);
                }
            }
        }
        File[] stored = storageDir.listFiles();
        if (stored != null) {
            for (File f : stored) {
                String n = f.getName();
                if (!n.endsWith(".json")) {
                    continue;
                }
                String stem = n.substring(0, n.length() - 5);
                int dot = stem.lastIndexOf('.');
                String pid = dot > 0 ? stem.substring(0, dot) : stem;
                if (find(pid) == null) {
                    f.delete();
                }
            }
        }
    }

    boolean checkToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), tokenBytes);
    }

    private void handleReport(String id, String kind, String message, String json) {
        String stack = "";
        String url = "";
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                stack = o.optString("stack", "");
                url = o.optString("url", "");
            } catch (JSONException ignored) {
            }
        }
        String k = kind == null || kind.isEmpty() ? "error" : kind;
        if (k.length() > 16) {
            k = k.substring(0, 16);
        }
        recordError(id, k, message, stack, url);
    }

    private synchronized boolean allowLog(String id) {
        long now = System.currentTimeMillis();
        long[] state = logRate.get(id);
        if (state == null || now - state[0] > LOG_RATE_WINDOW_MS) {
            state = new long[]{now, 0L};
            logRate.put(id, state);
        }
        state[1]++;
        return state[1] <= LOG_RATE_LIMIT;
    }

    public synchronized String storageGet(String id, String key) {
        if (key == null || key.length() > MAX_STORAGE_KEY_LENGTH) {
            return null;
        }
        JSONObject o = loadStorage(id);
        return o.has(key) ? o.optString(key, null) : null;
    }

    public synchronized boolean storageSet(String id, String key, String value) {
        if (key == null || key.isEmpty() || key.length() > MAX_STORAGE_KEY_LENGTH) {
            return false;
        }
        String v = value == null ? "" : value;
        if (v.length() > MAX_STORAGE_VALUE_LENGTH) {
            return false;
        }
        JSONObject o = loadStorage(id);
        if (!o.has(key) && o.length() >= MAX_STORAGE_KEYS) {
            return false;
        }
        try {
            o.put(key, v);
        } catch (JSONException e) {
            return false;
        }
        return saveStorage(id, o);
    }

    public synchronized boolean storageRemove(String id, String key) {
        if (key == null) {
            return false;
        }
        JSONObject o = loadStorage(id);
        if (!o.has(key)) {
            return true;
        }
        o.remove(key);
        return saveStorage(id, o);
    }

    private JSONObject loadStorage(String id) {
        JSONObject cached = storageCache.get(id);
        if (cached != null) {
            return cached;
        }
        JSONObject o = new JSONObject();
        File f = storageFile(id);
        if (f.isFile() && f.length() <= MAX_STORAGE_FILE_BYTES) {
            try {
                o = new JSONObject(readFileText(f));
            } catch (Exception e) {
                o = new JSONObject();
            }
        }
        storageCache.put(id, o);
        return o;
    }

    private boolean saveStorage(String id, JSONObject o) {
        String text = o.toString();
        if (text.length() > MAX_STORAGE_FILE_BYTES) {
            return false;
        }
        return writeAtomic(storageFile(id), text);
    }

    private File storageFile(String id) {
        return new File(storageDir, id + "." + processTag + ".json");
    }

    private void deleteStorageFiles(String id) {
        File[] files = storageDir.listFiles();
        if (files == null) {
            return;
        }
        String prefix = id + ".";
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(prefix) && n.endsWith(".json") && n.indexOf('.', prefix.length()) == n.length() - 5) {
                f.delete();
            }
        }
    }

    private void handleAppCrash(Thread thread, Throwable t) {
        List<String> ids;
        synchronized (this) {
            if (lastInjectedIds.isEmpty() || System.currentTimeMillis() - lastInjectionAt > ACTIVE_WINDOW_MS) {
                return;
            }
            ids = new ArrayList<>(lastInjectedIds);
        }
        String summary = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (looksRelated(thread, t)) {
            recordCrash(ids, "アプリクラッシュ(" + summary + ")");
        } else {
            for (String id : ids) {
                logger.log("W", id, "読み込み直後にアプリがクラッシュしましたがプラグインとの関連は不明です: " + summary);
            }
        }
    }

    private static boolean looksRelated(Thread thread, Throwable t) {
        String name = thread == null || thread.getName() == null ? "" : thread.getName();
        if (name.contains("JavaBridge") || name.startsWith("Chrome") || name.startsWith("Cr")) {
            return true;
        }
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            StackTraceElement[] st = cur.getStackTrace();
            for (int i = 0; i < st.length && i < 40; i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("com.coara.browser.plugin") || cn.startsWith("org.chromium")
                        || cn.startsWith("android.webkit") || cn.startsWith("com.android.webview")
                        || cn.startsWith("com.google.android.webview")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private void writeMarker(long now) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", now);
            JSONArray a = new JSONArray();
            for (String id : lastInjectedIds) {
                a.put(id);
            }
            o.put("ids", a);
            writeAtomic(activeMarker, o.toString());
            lastMarkerWrite = now;
        } catch (Exception ignored) {
        }
    }

    private void clearActiveMarker() {
        try {
            if (activeMarker.exists()) {
                activeMarker.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void consumeActiveMarker() {
        if (!activeMarker.exists()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(readFileText(activeMarker));
            long t = o.optLong("t", 0L);
            JSONArray a = o.optJSONArray("ids");
            activeMarker.delete();
            if (a == null || System.currentTimeMillis() - t > MARKER_MAX_AGE_MS) {
                return;
            }
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                ids.add(a.optString(i));
            }
            if (!ids.isEmpty()) {
                recordCrash(ids, "前回のプロセスがページ読み込み直後に異常終了した可能性があります");
            }
        } catch (Exception e) {
            activeMarker.delete();
        }
    }

    private void disableLocked(PluginInfo p, String reason) {
        p.enabled = false;
        p.disabledReason = reason;
        p.disabledAt = System.currentTimeMillis();
        logger.log("W", p.id, "自動的に無効化しました: " + reason);
        final PluginInfo copy = p.copy();
        final String shown = reason;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, "拡張機能「" + copy.title + "」を無効化しました: " + shown,
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
                for (Listener l : listeners) {
                    try {
                        l.onPluginAutoDisabled(copy, shown);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void notifyRuntimeChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    try {
                        l.onPluginRuntimeChanged();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    try {
                        l.onPluginsChanged();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private PluginInfo find(String id) {
        if (id == null) {
            return null;
        }
        for (PluginInfo p : plugins) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    private String uniqueId(String base) {
        String candidate = base;
        int n = 2;
        while (find(candidate) != null || new File(root, candidate).exists()) {
            candidate = base + "-" + n;
            n++;
        }
        return candidate;
    }

    private void ensureLoadedLocked() {
        if (!loaded) {
            loadLocked();
            loaded = true;
        }
    }

    private void loadLocked() {
        plugins.clear();
        if (indexFile.exists()) {
            try {
                JSONArray arr = new JSONArray(readFileText(indexFile));
                for (int i = 0; i < arr.length(); i++) {
                    PluginInfo p = PluginInfo.fromJson(arr.getJSONObject(i));
                    if (!p.id.isEmpty() && new File(root, p.id).isDirectory()) {
                        plugins.add(p);
                    }
                }
            } catch (Exception e) {
                logger.log("E", "-", "インデックスの読み込みに失敗: " + e);
            }
        }
        indexStamp = stamp();
        loaded = true;
    }

    private void saveLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (PluginInfo p : plugins) {
                arr.put(p.toJson());
            }
            writeAtomic(indexFile, arr.toString());
        } catch (JSONException e) {
            logger.log("E", "-", "インデックスの保存に失敗: " + e);
        }
        indexStamp = stamp();
        version++;
    }

    private long stamp() {
        return indexFile.exists() ? indexFile.lastModified() * 31L + indexFile.length() : 0L;
    }

    private static boolean writeAtomic(File target, String text) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
                return true;
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
            return true;
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    private static String readFileText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 4L * 1024L * 1024L) {
                    throw new IOException("file too large");
                }
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            int offset = 0;
            if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            return new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
