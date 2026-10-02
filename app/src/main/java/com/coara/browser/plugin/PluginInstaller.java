package com.coara.browser.plugin;

import android.graphics.BitmapFactory;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private static final long MAX_ZIP_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_ICON_BYTES = 1024 * 1024;
    private static final int MAX_SCRIPT_TOTAL = 512 * 1024;
    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "js", "css", "xml", "json", "txt", "html", "htm", "csv", "md"));

    private PluginInstaller() {
    }

    public static ParsedPlugin parse(InputStream in) throws PluginInstallException, IOException {
        if (in == null) {
            throw new PluginInstallException("ファイルを開けません");
        }
        byte[] zipBytes = readLimited(in, MAX_ZIP_BYTES, "zipファイルが大きすぎます(上限16MB)");
        List<String> warnings = new ArrayList<>();
        Map<String, byte[]> files;
        try {
            files = readZip(zipBytes, StandardCharsets.UTF_8, warnings);
        } catch (IllegalArgumentException e) {
            warnings.clear();
            try {
                files = readZip(zipBytes, Charset.forName("Shift_JIS"), warnings);
            } catch (IllegalArgumentException e2) {
                warnings.clear();
                files = readZip(zipBytes, StandardCharsets.ISO_8859_1, warnings);
            }
        }
        if (files.isEmpty()) {
            throw new PluginInstallException("zip内に有効なファイルがありません");
        }

        List<String> jsNames = namesWithExt(files, "js");
        if (jsNames.isEmpty()) {
            throw new PluginInstallException("zip内にjsファイルがありません");
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        List<String> xmlScripts = new ArrayList<>();
        List<String> xmlStyles = new ArrayList<>();
        String[] textTitle = new String[]{null};
        for (String xmlName : orderedXmlNames(files)) {
            try {
                parseXml(files.get(xmlName), attrs, xmlScripts, xmlStyles, textTitle);
            } catch (Exception e) {
                warnings.add("xmlを解析できません(" + xmlName + "): " + e.getMessage());
            }
        }

        String rawTitle = attrs.get("plgtitle");
        if ((rawTitle == null || rawTitle.trim().isEmpty()) && textTitle[0] != null) {
            rawTitle = textTitle[0];
        }
        rawTitle = cleanText(rawTitle);
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

        PluginInfo info = new PluginInfo();
        info.title = title;
        info.id = makeId(attrs.get("plgid"), title, fallback);
        info.version = limit(cleanText(attrs.get("plgversion")), 24);
        info.author = limit(cleanText(attrs.get("plgauthor")), 32);
        info.description = limit(cleanText(attrs.get("plgdescription")), 200);
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

        info.scripts.addAll(resolveList(xmlScripts, files, "js", jsNames, warnings));
        info.styles.addAll(resolveList(xmlStyles, files, "css", namesWithExt(files, "css"), warnings));
        if (info.scripts.isEmpty()) {
            throw new PluginInstallException("実行できるjsファイルがありません");
        }

        int scriptBytes = 0;
        for (String s : info.scripts) {
            scriptBytes += files.get(s).length;
        }
        for (String s : info.styles) {
            scriptBytes += files.get(s).length;
        }
        if (scriptBytes > MAX_SCRIPT_TOTAL) {
            throw new PluginInstallException("js/cssの合計サイズが上限(512KB)を超えています");
        }

        info.icon = pickIcon(files, warnings);

        Map<String, byte[]> kept = new LinkedHashMap<>(files);
        for (String n : kept.keySet()) {
            if (TEXT_EXT.contains(extOf(n))) {
                info.resources.add(n);
            }
        }
        return new ParsedPlugin(info, kept, fallback, truncated, warnings);
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
                String base = norm.substring(norm.lastIndexOf('/') + 1);
                if (base.isEmpty() || base.startsWith(".")) {
                    continue;
                }
                String ext = extOf(base);
                int limit;
                if (ext.equals("png")) {
                    limit = MAX_ICON_BYTES;
                } else if (TEXT_EXT.contains(ext)) {
                    limit = MAX_TEXT_BYTES;
                } else {
                    continue;
                }
                byte[] data = readLimited(zin, limit, "ファイルが大きすぎます: " + base);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new PluginInstallException("展開後の合計サイズが上限(8MB)を超えています");
                }
                String name = sanitizeName(base);
                if (files.containsKey(name)) {
                    warnings.add("同名ファイルを無視: " + base);
                    continue;
                }
                files.put(name, data);
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
                String norm = ref.replace('\\', '/');
                String name = sanitizeName(norm.substring(norm.lastIndexOf('/') + 1));
                if (!files.containsKey(name) || !extOf(name).equals(ext)) {
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

    private static String pickIcon(Map<String, byte[]> files, List<String> warnings) {
        List<String> pngs = namesWithExt(files, "png");
        String chosen = null;
        for (String n : pngs) {
            if (n.equalsIgnoreCase("icon.png")) {
                chosen = n;
                break;
            }
        }
        if (chosen == null && !pngs.isEmpty()) {
            chosen = pngs.get(0);
        }
        if (chosen == null) {
            warnings.add("icon.pngが見つかりません");
            return "";
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] d = files.get(chosen);
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 2048 || o.outHeight > 2048) {
                warnings.add("アイコン画像が不正です: " + chosen);
                return "";
            }
        } catch (Exception e) {
            warnings.add("アイコン画像を読み込めません: " + chosen);
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

    static String sanitizeName(String base) {
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
