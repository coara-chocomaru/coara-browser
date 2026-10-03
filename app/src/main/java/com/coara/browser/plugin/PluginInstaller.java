package com.coara.browser.plugin;

import android.graphics.BitmapFactory;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class PluginInstaller {
    public static final int MAX_TITLE_CODE_POINTS = 16;

    public interface Fetcher {
        byte[] fetch(String url, int maxBytes) throws IOException;
    }

    public static final class HttpFetcher implements Fetcher {
        @Override
        public byte[] fetch(String url, int maxBytes) throws IOException {
            String lower = url.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new IOException("unsupported scheme");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) CoaraBrowser");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0L;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > maxBytes) {
                            throw new IOException("too large");
                        }
                        bos.write(buf, 0, n);
                    }
                    return bos.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }
    }

    private static final long MAX_ZIP_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 12L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SCRIPT_TOTAL = 2 * 1024 * 1024;
    private static final int MAX_REQUIRES = 8;
    private static final int MAX_RESOURCES = 10;
    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "js", "css", "xml", "json", "txt", "html", "htm", "csv", "md", "mjs"));
    private static final Set<String> ASSET_EXT = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "otf", "bmp"));

    private PluginInstaller() {
    }

    public static ParsedPlugin parse(InputStream in) throws PluginInstallException, IOException {
        return parse(in, "", new HttpFetcher());
    }

    public static ParsedPlugin parse(InputStream in, String fileName, Fetcher fetcher)
            throws PluginInstallException, IOException {
        if (in == null) {
            throw new PluginInstallException("ファイルを開けません");
        }
        byte[] raw = readLimited(in, MAX_ZIP_BYTES, "ファイルが大きすぎます(上限16MB)");
        if (raw.length == 0) {
            throw new PluginInstallException("ファイルが空です");
        }
        List<String> warnings = new ArrayList<>();
        raw = stripCrx(raw);
        Map<String, byte[]> files;
        String hint = fileName == null ? "" : fileName;
        if (isZip(raw)) {
            try {
                files = readZip(raw, StandardCharsets.UTF_8, warnings);
            } catch (IllegalArgumentException e) {
                warnings.clear();
                try {
                    files = readZip(raw, Charset.forName("Shift_JIS"), warnings);
                } catch (IllegalArgumentException e2) {
                    warnings.clear();
                    files = readZip(raw, StandardCharsets.ISO_8859_1, warnings);
                }
            }
            files = stripCommonRoot(files);
        } else {
            files = singleFile(raw, hint);
        }
        if (files.isEmpty()) {
            throw new PluginInstallException("有効なファイルがありません");
        }
        if (fetcher == null) {
            fetcher = new HttpFetcher();
        }

        PluginInfo info = new PluginInfo();
        boolean built = false;
        if (files.containsKey("manifest.json") && WebExtParser.isManifest(files.get("manifest.json"))) {
            WebExtParser.parse(info, files, warnings);
            built = true;
        }
        if (!built) {
            Map<String, String> attrs = new LinkedHashMap<>();
            List<String> xmlScripts = new ArrayList<>();
            List<String> xmlStyles = new ArrayList<>();
            String[] textTitle = new String[]{null};
            boolean xmlHit = false;
            for (String xmlName : orderedXmlNames(files)) {
                try {
                    parseXml(files.get(xmlName), attrs, xmlScripts, xmlStyles, textTitle);
                } catch (Exception e) {
                    warnings.add("xmlを解析できません(" + xmlName + "): " + e.getMessage());
                }
            }
            for (String k : attrs.keySet()) {
                if (k.startsWith("plg")) {
                    xmlHit = true;
                    break;
                }
            }
            if (!xmlHit && textTitle[0] != null) {
                xmlHit = true;
            }
            String userScriptName = xmlHit ? null : findWithMeta(files, "js");
            String userStyleName = (xmlHit || userScriptName != null) ? null : findUserStyle(files);
            if (userScriptName != null) {
                buildUserScript(info, files, userScriptName, fetcher, warnings);
            } else if (userStyleName != null) {
                buildUserStyle(info, files, userStyleName, warnings);
            } else {
                buildCoara(info, files, attrs, xmlScripts, xmlStyles, textTitle[0], warnings);
            }
        }
        return finish(info, files, warnings);
    }

    private static ParsedPlugin finish(PluginInfo info, Map<String, byte[]> files, List<String> warnings)
            throws PluginInstallException {
        String rawTitle = cleanText(info.title);
        boolean fallback = rawTitle.isEmpty();
        boolean truncated = false;
        String title;
        if (fallback) {
            title = "no_name-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        } else {
            title = rawTitle;
            if (title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS) {
                title = title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS));
                truncated = true;
                warnings.add("プラグイン名を16文字に切り詰めました");
            }
        }
        info.title = title;
        info.id = makeId(info.id, title, fallback);
        info.version = limit(cleanText(info.version), 24);
        info.author = limit(cleanText(info.author), 32);
        info.description = limit(cleanText(info.description), 200);
        info.homepage = limit(cleanText(info.homepage), 200);
        if (info.entries.isEmpty() && info.scripts.isEmpty() && info.styles.isEmpty()) {
            throw new PluginInstallException("実行できるjs/cssがありません");
        }
        long bytes = 0L;
        for (PluginEntry e : info.entries()) {
            for (String s : e.scripts) {
                byte[] d = files.get(s);
                bytes += d == null ? 0 : d.length;
            }
            for (String s : e.styles) {
                byte[] d = files.get(s);
                bytes += d == null ? 0 : d.length;
            }
        }
        if (bytes > MAX_SCRIPT_TOTAL) {
            throw new PluginInstallException("js/cssの合計サイズが上限(2MB)を超えています");
        }
        if (info.icon == null || info.icon.isEmpty() || !files.containsKey(info.icon)) {
            info.icon = pickIcon(files, warnings);
        } else if (!iconValid(files.get(info.icon))) {
            warnings.add("アイコン画像が不正です: " + info.icon);
            info.icon = "";
        }
        info.resources.clear();
        info.assets.clear();
        for (String n : files.keySet()) {
            String ext = extOf(n);
            if (TEXT_EXT.contains(ext)) {
                info.resources.add(n);
            } else if (ASSET_EXT.contains(ext)) {
                info.assets.add(n);
            }
        }
        return new ParsedPlugin(info, new LinkedHashMap<>(files), fallback, truncated, warnings);
    }

    private static void aggregate(PluginInfo info) {
        for (PluginEntry e : info.entries) {
            for (String s : e.matches) {
                if (!info.matches.contains(s)) {
                    info.matches.add(s);
                }
            }
            for (String s : e.excludes) {
                if (!info.excludes.contains(s)) {
                    info.excludes.add(s);
                }
            }
            for (String s : e.scripts) {
                if (!info.scripts.contains(s)) {
                    info.scripts.add(s);
                }
            }
            for (String s : e.styles) {
                if (!info.styles.contains(s)) {
                    info.styles.add(s);
                }
            }
            info.allFrames |= e.allFrames;
        }
        if (!info.entries.isEmpty()) {
            info.runAt = info.entries.get(0).runAt;
        }
    }

    private static void buildCoara(PluginInfo info, Map<String, byte[]> files, Map<String, String> attrs,
                                   List<String> xmlScripts, List<String> xmlStyles, String textTitle,
                                   List<String> warnings) throws PluginInstallException {
        List<String> jsNames = namesWithExt(files, "js");
        if (jsNames.isEmpty()) {
            throw new PluginInstallException("zip内にjsファイルがありません");
        }
        String rawTitle = attrs.get("plgtitle");
        if ((rawTitle == null || rawTitle.trim().isEmpty()) && textTitle != null) {
            rawTitle = textTitle;
        }
        info.format = PluginInfo.FORMAT_COARA;
        info.title = rawTitle == null ? "" : rawTitle;
        info.id = attrs.get("plgid") == null ? "" : attrs.get("plgid");
        info.version = nz(attrs.get("plgversion"));
        info.author = nz(attrs.get("plgauthor"));
        info.description = nz(attrs.get("plgdescription"));
        info.homepage = nz(attrs.get("plghomepage"));
        info.runAt = PluginInfo.normalizeRunAt(attrs.get("plgrunat"));
        info.allFrames = parseBool(attrs.get("plgallframes"));
        for (String p : PluginPatterns.parseList(attrs.get("plgmatches"))) {
            if (PluginPatterns.toRegex(p) == null) {
                warnings.add("無効な対象パターンを無視: " + p);
            } else {
                info.matches.add(p);
            }
        }
        if (info.matches.isEmpty()) {
            info.matches.addAll(PluginPatterns.DEFAULT_MATCHES);
        }
        for (String p : PluginPatterns.parseList(attrs.get("plgexcludes"))) {
            if (PluginPatterns.toRegex(p) == null) {
                warnings.add("無効な除外パターンを無視: " + p);
            } else {
                info.excludes.add(p);
            }
        }
        for (String perm : PluginPatterns.parseList(nz(attrs.get("plgpermissions")).replace(' ', ','))) {
            String v = perm.toLowerCase(Locale.US);
            if (v.equals(PluginInfo.PERM_XHR) || v.equals(PluginInfo.PERM_CLIPBOARD)
                    || v.equals(PluginInfo.PERM_NOTIFICATION) || v.equals(PluginInfo.PERM_OPENTAB)) {
                if (!info.permissions.contains(v)) {
                    info.permissions.add(v);
                }
            } else {
                warnings.add("未対応の権限を無視: " + perm);
            }
        }
        for (String host : PluginPatterns.parseList(nz(attrs.get("plgconnects")).replace(' ', ','))) {
            info.connects.add(host.toLowerCase(Locale.US));
        }
        info.scripts.addAll(resolveList(xmlScripts, files, "js", jsNames, warnings));
        info.styles.addAll(resolveList(xmlStyles, files, "css", namesWithExt(files, "css"), warnings));
        if (info.scripts.isEmpty()) {
            throw new PluginInstallException("実行できるjsファイルがありません");
        }
    }

    private static void buildUserScript(PluginInfo info, Map<String, byte[]> files, String name,
                                        Fetcher fetcher, List<String> warnings) throws PluginInstallException {
        String src = WebExtParser.decode(files.get(name));
        UserScriptParser.Meta m = UserScriptParser.parse(src);
        info.format = PluginInfo.FORMAT_USERSCRIPT;
        info.title = m.name;
        info.id = m.name;
        info.version = m.version;
        info.author = m.author;
        info.description = m.description;
        info.homepage = m.homepage;
        PluginEntry entry = new PluginEntry();
        UserScriptParser.fillEntry(m, entry, warnings);

        int counter = 0;
        int fetched = 0;
        for (String url : m.requires) {
            String u = url.startsWith("//") ? "https:" + url : url;
            String stored = lookupLocal(files, u);
            if (stored == null) {
                if (fetched >= MAX_REQUIRES) {
                    warnings.add("@requireが多すぎるため無視: " + url);
                    continue;
                }
                byte[] data = fetchSafe(fetcher, u, MAX_TEXT_BYTES, warnings, "@require");
                if (data == null) {
                    continue;
                }
                fetched++;
                counter++;
                stored = uniquePath(files, "lib/req_" + counter + "_" + sanitizeSegment(baseOf(u)));
                if (!stored.endsWith(".js")) {
                    stored = stored + ".js";
                }
                files.put(stored, data);
            }
            if (!entry.scripts.contains(stored)) {
                entry.scripts.add(stored);
            }
        }
        entry.scripts.add(name);

        int resCount = 0;
        for (Map.Entry<String, String> r : m.resources.entrySet()) {
            String u = r.getValue();
            int hash = u.indexOf('#');
            if (hash > 0) {
                u = u.substring(0, hash);
            }
            if (u.startsWith("//")) {
                u = "https:" + u;
            }
            String stored = lookupLocal(files, u);
            if (stored == null) {
                if (resCount >= MAX_RESOURCES) {
                    warnings.add("@resourceが多すぎるため無視: " + r.getKey());
                    continue;
                }
                byte[] data = fetchSafe(fetcher, u, MAX_ASSET_BYTES, warnings, "@resource");
                if (data == null) {
                    continue;
                }
                resCount++;
                stored = uniquePath(files, "res/" + resCount + "_" + sanitizeSegment(baseOf(u)));
                files.put(stored, data);
            }
            info.resourceMap.put(r.getKey(), stored);
        }

        for (String g : m.grants) {
            String perm = UserScriptParser.permissionForGrant(g);
            if (perm != null && !info.permissions.contains(perm)) {
                info.permissions.add(perm);
            }
        }
        if (m.grants.isEmpty()) {
            String[][] probes = {
                    {"GM_xmlhttpRequest", PluginInfo.PERM_XHR}, {"GM.xmlHttpRequest", PluginInfo.PERM_XHR},
                    {"GM_setClipboard", PluginInfo.PERM_CLIPBOARD}, {"GM.setClipboard", PluginInfo.PERM_CLIPBOARD},
                    {"GM_notification", PluginInfo.PERM_NOTIFICATION}, {"GM.notification", PluginInfo.PERM_NOTIFICATION},
                    {"GM_openInTab", PluginInfo.PERM_OPENTAB}, {"GM.openInTab", PluginInfo.PERM_OPENTAB}};
            for (String[] p : probes) {
                if (src.contains(p[0]) && !info.permissions.contains(p[1])) {
                    info.permissions.add(p[1]);
                    warnings.add("@grant無しで" + p[0] + "の使用を検出したため権限「" + p[1] + "」を付与します");
                }
            }
        }
        for (String c : m.connects) {
            if (!c.equals("self") && !info.connects.contains(c)) {
                info.connects.add(c);
            }
        }
        if (!m.icon.isEmpty()) {
            String local = lookupLocal(files, m.icon);
            if (local != null && extOf(local).equals("png")) {
                info.icon = local;
            }
        }
        info.entries.add(entry);
        aggregate(info);
    }

    private static void buildUserStyle(PluginInfo info, Map<String, byte[]> files, String name,
                                       List<String> warnings) throws PluginInstallException {
        String src = WebExtParser.decode(files.get(name));
        UserStyleParser.Result r = UserStyleParser.parse(src, warnings);
        if (r.blocks.isEmpty()) {
            throw new PluginInstallException("適用できるCSSがありません");
        }
        info.format = PluginInfo.FORMAT_USERSTYLE;
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (base.toLowerCase(Locale.US).endsWith(".user.css")) {
            base = base.substring(0, base.length() - 9);
        } else if (base.toLowerCase(Locale.US).endsWith(".css")) {
            base = base.substring(0, base.length() - 4);
        }
        info.title = r.name.isEmpty() ? (base.equals("style") ? "" : base) : r.name;
        info.id = r.name;
        info.version = r.version;
        info.author = r.author;
        info.description = r.description;
        info.homepage = r.homepage;
        int i = 0;
        for (UserStyleParser.Block b : r.blocks) {
            i++;
            String path = uniquePath(files, "styles/style_" + i + ".css");
            files.put(path, b.css.getBytes(StandardCharsets.UTF_8));
            PluginEntry e = new PluginEntry();
            e.runAt = PluginInfo.RUN_AT_START;
            e.allFrames = true;
            e.matches.addAll(b.matches);
            e.styles.add(path);
            info.entries.add(e);
        }
        aggregate(info);
    }

    private static byte[] fetchSafe(Fetcher fetcher, String url, int max, List<String> warnings, String label) {
        try {
            byte[] d = fetcher.fetch(url, max);
            if (d == null || d.length == 0) {
                warnings.add(label + "が空です: " + url);
                return null;
            }
            return d;
        } catch (Exception e) {
            warnings.add(label + "を取得できません: " + url + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private static String lookupLocal(Map<String, byte[]> files, String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("//")) {
            String p = refPath(url);
            if (p != null && files.containsKey(p)) {
                return p;
            }
        }
        String base = sanitizeSegment(baseOf(url));
        for (String k : files.keySet()) {
            if (k.equals(base) || k.endsWith("/" + base)) {
                return k;
            }
        }
        return null;
    }

    private static String baseOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        int s = u.lastIndexOf('/');
        String b = s >= 0 ? u.substring(s + 1) : u;
        return b.isEmpty() ? "file" : b;
    }

    private static String uniquePath(Map<String, byte[]> files, String path) {
        if (!files.containsKey(path)) {
            return path;
        }
        int dot = path.lastIndexOf('.');
        String stem = dot > 0 ? path.substring(0, dot) : path;
        String ext = dot > 0 ? path.substring(dot) : "";
        int n = 2;
        while (files.containsKey(stem + "_" + n + ext)) {
            n++;
        }
        return stem + "_" + n + ext;
    }

    private static String findWithMeta(Map<String, byte[]> files, String ext) {
        List<String> names = namesWithExt(files, ext);
        String firstUser = null;
        for (String n : names) {
            if (n.toLowerCase(Locale.US).endsWith(".user.js")) {
                if (firstUser == null) {
                    firstUser = n;
                }
                if (UserScriptParser.hasMetaBlock(WebExtParser.decode(files.get(n)))) {
                    return n;
                }
            }
        }
        for (String n : names) {
            if (UserScriptParser.hasMetaBlock(WebExtParser.decode(files.get(n)))) {
                return n;
            }
        }
        return firstUser;
    }

    private static String findUserStyle(Map<String, byte[]> files) {
        List<String> names = namesWithExt(files, "css");
        if (names.isEmpty() || !namesWithExt(files, "js").isEmpty()) {
            return null;
        }
        for (String n : names) {
            if (UserStyleParser.hasMetaBlock(WebExtParser.decode(files.get(n)))) {
                return n;
            }
        }
        for (String n : names) {
            if (n.toLowerCase(Locale.US).endsWith(".user.css")) {
                return n;
            }
        }
        return names.get(0);
    }

    private static Map<String, byte[]> singleFile(byte[] raw, String hint) throws PluginInstallException {
        String text = WebExtParser.decode(raw);
        Map<String, byte[]> files = new LinkedHashMap<>();
        String lowerHint = hint.toLowerCase(Locale.US);
        String safeHint = hint.isEmpty() ? "" : sanitizeSegment(hint);
        if (UserScriptParser.hasMetaBlock(text)) {
            files.put(safeHint.toLowerCase(Locale.US).endsWith(".js") ? safeHint : "script.user.js", raw);
        } else if (UserStyleParser.hasMetaBlock(text) || lowerHint.endsWith(".css")) {
            files.put(safeHint.toLowerCase(Locale.US).endsWith(".css") ? safeHint : "style.user.css", raw);
        } else if (lowerHint.endsWith(".js") || lowerHint.endsWith(".mjs") || text.contains("function")
                || text.contains("document.") || text.contains("window.")) {
            files.put(safeHint.toLowerCase(Locale.US).endsWith(".js") ? safeHint : "script.js", raw);
        } else {
            throw new PluginInstallException("対応していないファイル形式です(zip / .user.js / .js / .css / .xpi / .crx)");
        }
        return files;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K';
    }

    private static byte[] stripCrx(byte[] d) {
        if (d.length < 16 || d[0] != 'C' || d[1] != 'r' || d[2] != '2' || d[3] != '4') {
            return d;
        }
        int version = le32(d, 4);
        int start;
        if (version == 2) {
            start = 16 + le32(d, 8) + le32(d, 12);
        } else if (version == 3) {
            start = 12 + le32(d, 8);
        } else {
            return d;
        }
        if (start <= 0 || start >= d.length) {
            return d;
        }
        return Arrays.copyOfRange(d, start, d.length);
    }

    private static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static Map<String, byte[]> stripCommonRoot(Map<String, byte[]> files) {
        String root = null;
        for (String k : files.keySet()) {
            int s = k.indexOf('/');
            if (s <= 0) {
                return files;
            }
            String first = k.substring(0, s);
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return files;
            }
        }
        if (root == null) {
            return files;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            out.put(e.getKey().substring(root.length() + 1), e.getValue());
        }
        return out;
    }

    private static Map<String, byte[]> readZip(byte[] zipBytes, Charset charset, List<String> warnings)
            throws PluginInstallException, IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0L;
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw new PluginInstallException("zip内のファイル数が多すぎます(上限" + MAX_ENTRIES + ")");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String norm = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (norm.startsWith("__MACOSX/") || norm.contains("/__MACOSX/")) {
                    continue;
                }
                String path = sanitizePath(norm);
                if (path == null) {
                    continue;
                }
                String ext = extOf(path);
                int limit;
                if (ASSET_EXT.contains(ext)) {
                    limit = MAX_ASSET_BYTES;
                } else if (TEXT_EXT.contains(ext)) {
                    limit = MAX_TEXT_BYTES;
                } else {
                    continue;
                }
                byte[] data = readLimited(zin, limit, "ファイルが大きすぎます: " + path);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new PluginInstallException("展開後の合計サイズが上限(12MB)を超えています");
                }
                if (files.containsKey(path)) {
                    warnings.add("同名ファイルを無視: " + norm);
                    continue;
                }
                files.put(path, data);
            }
        } catch (ZipException e) {
            throw new PluginInstallException("zipを読み込めません: " + e.getMessage());
        }
        return files;
    }

    private static byte[] readLimited(InputStream in, long limit, String errorMessage)
            throws PluginInstallException, IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new PluginInstallException(errorMessage);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static List<String> orderedXmlNames(Map<String, byte[]> files) {
        List<String> names = namesWithExt(files, "xml");
        List<String> preferred = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String n : names) {
            String l = n.toLowerCase(Locale.US);
            if (l.equals("plugin.xml") || l.equals("manifest.xml")) {
                preferred.add(n);
            } else {
                rest.add(n);
            }
        }
        Collections.sort(preferred, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(rest, String.CASE_INSENSITIVE_ORDER);
        preferred.addAll(rest);
        return preferred;
    }

    private static void parseXml(byte[] data, Map<String, String> attrs, List<String> scripts,
                                 List<String> styles, String[] textTitle) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(data), null);
        String currentTag = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName() == null ? "" : p.getName().toLowerCase(Locale.US);
                currentTag = tag;
                String src = null;
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String key = p.getAttributeName(i);
                    if (key == null) {
                        continue;
                    }
                    key = key.toLowerCase(Locale.US);
                    int colon = key.lastIndexOf(':');
                    if (colon >= 0) {
                        key = key.substring(colon + 1);
                    }
                    String val = p.getAttributeValue(i);
                    if (val == null) {
                        continue;
                    }
                    if (key.equals("src") || key.equals("href")) {
                        src = val.trim();
                    }
                    if (!attrs.containsKey(key)) {
                        attrs.put(key, val);
                    }
                }
                if (src != null && !src.isEmpty()) {
                    if (tag.equals("script") || tag.equals("js")) {
                        scripts.add(src);
                    } else if (tag.equals("style") || tag.equals("css")) {
                        styles.add(src);
                    }
                }
            } else if (ev == XmlPullParser.TEXT) {
                if ("plgtitle".equals(currentTag) && textTitle[0] == null) {
                    String t = p.getText();
                    if (t != null && !t.trim().isEmpty()) {
                        textTitle[0] = t.trim();
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                currentTag = null;
            }
            ev = p.next();
        }
    }

    private static List<String> resolveList(List<String> referenced, Map<String, byte[]> files,
                                            String ext, List<String> all, List<String> warnings) {
        List<String> out = new ArrayList<>();
        if (!referenced.isEmpty()) {
            for (String ref : referenced) {
                String name = refPath(ref);
                if (name == null || !files.containsKey(name)) {
                    name = null;
                    String base = sanitizeSegment(baseOf(ref));
                    for (String k : files.keySet()) {
                        if (k.equals(base) || k.endsWith("/" + base)) {
                            name = k;
                            break;
                        }
                    }
                }
                if (name == null || !extOf(name).equals(ext)) {
                    warnings.add("参照されたファイルが見つかりません: " + ref);
                    continue;
                }
                if (!out.contains(name)) {
                    out.add(name);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        out.addAll(all);
        return out;
    }

    private static boolean iconValid(byte[] d) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= 2048 && o.outHeight <= 2048;
        } catch (Exception e) {
            return false;
        }
    }

    private static String pickIcon(Map<String, byte[]> files, List<String> warnings) {
        List<String> pngs = namesWithExt(files, "png");
        String chosen = null;
        for (String n : pngs) {
            if (n.equalsIgnoreCase("icon.png")) {
                chosen = n;
                break;
            }
        }
        if (chosen == null) {
            for (String n : pngs) {
                if (n.toLowerCase(Locale.US).endsWith("/icon.png")) {
                    chosen = n;
                    break;
                }
            }
        }
        if (chosen == null && !pngs.isEmpty()) {
            chosen = pngs.get(0);
        }
        if (chosen == null) {
            if (files.size() > 1) {
                warnings.add("icon.pngが見つかりません");
            }
            return "";
        }
        if (!iconValid(files.get(chosen))) {
            warnings.add("アイコン画像が不正です: " + chosen);
            return "";
        }
        return chosen;
    }

    private static List<String> namesWithExt(Map<String, byte[]> files, String ext) {
        List<String> out = new ArrayList<>();
        for (String n : files.keySet()) {
            if (extOf(n).equals(ext)) {
                out.add(n);
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    static String refPath(String ref) {
        if (ref == null) {
            return null;
        }
        String r = ref.trim();
        int q = r.indexOf('?');
        if (q >= 0) {
            r = r.substring(0, q);
        }
        int h = r.indexOf('#');
        if (h >= 0) {
            r = r.substring(0, h);
        }
        return sanitizePath(r.replace('\\', '/'));
    }

    static String sanitizePath(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || part.startsWith(".")) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(sanitizeSegment(part));
        }
        if (sb.length() == 0 || sb.length() > 200) {
            return null;
        }
        return sb.toString();
    }

    static String sanitizeSegment(String base) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 64; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || s.startsWith(".")) {
            s = "_" + s;
        }
        return s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String limit(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static boolean parseBool(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.US);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    static String makeId(String explicitId, String title, boolean fallback) {
        String id = "";
        if (explicitId != null && !explicitId.trim().isEmpty()) {
            id = slug(explicitId);
        }
        if (id.isEmpty()) {
            id = slug(title);
        }
        if (id.isEmpty()) {
            id = "plg_" + shortHash(title);
        }
        return id;
    }

    private static String slug(String raw) {
        StringBuilder sb = new StringBuilder();
        String lower = raw.trim().toLowerCase(Locale.US);
        boolean lastUnderscore = false;
        boolean hasAlnum = false;
        for (int i = 0; i < lower.length() && sb.length() < 32; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                hasAlnum = true;
                lastUnderscore = false;
            } else if (c == '-' || c == '_') {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        if (!hasAlnum) {
            return "";
        }
        String s = sb.toString();
        while (s.startsWith("_") || s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("_") || s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.nanoTime());
        }
    }
}
