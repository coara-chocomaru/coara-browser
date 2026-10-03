package com.coara.browser.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebExtParser {
    private static final Pattern MSG_REF = Pattern.compile("__MSG_([A-Za-z0-9_@]+)__");
    private static final Set<String> SILENT_PERMISSIONS = new HashSet<>(Arrays.asList(
            "storage", "unlimitedstorage", "activetab", "clipboardwrite", "notifications", "tabs",
            "clipboardread", "geolocation", "idle", "scripting", "webnavigation"));
    private static final int MAX_GLOB_COMBINATIONS = 64;

    private WebExtParser() {
    }

    public static boolean isManifest(byte[] data) {
        if (data == null || data.length == 0 || data.length > 1024 * 1024) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(decode(data));
            return o.has("manifest_version") || o.has("content_scripts");
        } catch (Exception e) {
            return false;
        }
    }

    static String decode(byte[] data) {
        int off = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        return new String(data, off, data.length - off, StandardCharsets.UTF_8);
    }

    public static void parse(PluginInfo info, Map<String, byte[]> files, List<String> warnings)
            throws PluginInstallException {
        JSONObject m;
        try {
            m = new JSONObject(decode(files.get("manifest.json")));
        } catch (Exception e) {
            throw new PluginInstallException("manifest.jsonを解析できません: " + e.getMessage());
        }
        info.format = PluginInfo.FORMAT_WEBEXT;
        info.defaultLocale = m.optString("default_locale", "");
        Messages msgs = Messages.load(files, info.defaultLocale);

        info.title = PluginInstaller.cleanText(msgs.resolve(m.optString("name", "")));
        info.version = PluginInstaller.cleanText(m.optString("version", ""));
        info.description = PluginInstaller.cleanText(msgs.resolve(m.optString("description", "")));
        info.homepage = PluginInstaller.cleanText(m.optString("homepage_url", ""));
        Object authorObj = m.opt("author");
        if (authorObj instanceof String) {
            info.author = PluginInstaller.cleanText(msgs.resolve((String) authorObj));
        } else {
            JSONObject dev = m.optJSONObject("developer");
            if (dev != null) {
                info.author = PluginInstaller.cleanText(dev.optString("name", ""));
            }
        }
        String geckoId = geckoId(m);
        info.id = geckoId;

        collectPermissions(m, info, warnings);
        warnUnsupported(m, warnings);

        JSONArray cs = m.optJSONArray("content_scripts");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                PluginEntry e = buildEntry(c, files, warnings, i);
                if (e != null) {
                    info.entries.add(e);
                }
            }
        }
        if (info.entries.isEmpty()) {
            throw new PluginInstallException("実行できるcontent_scriptsがありません(バックグラウンド専用の拡張機能は未対応です)");
        }
        for (PluginEntry e : info.entries) {
            for (String s : e.matches) {
                if (!info.matches.contains(s)) {
                    info.matches.add(s);
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
        info.runAt = info.entries.get(0).runAt;
        info.icon = pickIcon(m, files);
    }

    private static String geckoId(JSONObject m) {
        for (String key : new String[]{"browser_specific_settings", "applications"}) {
            JSONObject o = m.optJSONObject(key);
            if (o == null) {
                continue;
            }
            JSONObject g = o.optJSONObject("gecko");
            if (g != null) {
                String id = g.optString("id", "");
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        return "";
    }

    private static void collectPermissions(JSONObject m, PluginInfo info, List<String> warnings) {
        List<String> declared = new ArrayList<>();
        for (String key : new String[]{"permissions"}) {
            JSONArray a = m.optJSONArray(key);
            if (a == null) {
                continue;
            }
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if (o instanceof String) {
                    declared.add((String) o);
                }
            }
        }
        for (String p : declared) {
            String l = p.toLowerCase(Locale.US);
            if (l.contains("://") || l.equals("<all_urls>")) {
                continue;
            }
            switch (l) {
                case "clipboardwrite":
                    addPerm(info, PluginInfo.PERM_CLIPBOARD);
                    break;
                case "notifications":
                    addPerm(info, PluginInfo.PERM_NOTIFICATION);
                    break;
                case "tabs":
                case "activetab":
                    addPerm(info, PluginInfo.PERM_OPENTAB);
                    break;
                default:
                    if (!SILENT_PERMISSIONS.contains(l)) {
                        warnings.add("未対応の権限: " + p);
                    }
                    break;
            }
        }
    }

    private static void addPerm(PluginInfo info, String perm) {
        if (!info.permissions.contains(perm)) {
            info.permissions.add(perm);
        }
    }

    private static void warnUnsupported(JSONObject m, List<String> warnings) {
        if (m.has("background")) {
            warnings.add("バックグラウンドスクリプトは未対応です(content_scriptsのみ実行します)");
        }
        if (m.has("browser_action") || m.has("action") || m.has("page_action")) {
            warnings.add("ツールバーボタン/ポップアップは未対応です");
        }
        if (m.has("options_ui") || m.has("options_page")) {
            warnings.add("オプションページは未対応です");
        }
        if (m.has("commands")) {
            warnings.add("キーボードショートカット(commands)は未対応です");
        }
        if (m.has("devtools_page") || m.has("sidebar_action") || m.has("chrome_url_overrides")) {
            warnings.add("devtools/サイドバー/新規タブ置換は未対応です");
        }
        if (m.has("declarative_net_request") || m.has("user_scripts")) {
            warnings.add("declarativeNetRequest/user_scriptsは未対応です");
        }
    }

    private static PluginEntry buildEntry(JSONObject c, Map<String, byte[]> files, List<String> warnings, int index) {
        PluginEntry e = new PluginEntry();
        e.runAt = PluginInfo.normalizeRunAt(c.optString("run_at", PluginInfo.RUN_AT_END));
        e.allFrames = c.optBoolean("all_frames", false);
        List<String> baseMatches = new ArrayList<>();
        JSONArray ma = c.optJSONArray("matches");
        if (ma != null) {
            for (int i = 0; i < ma.length(); i++) {
                String raw = ma.optString(i, "");
                String r = PluginPatterns.fromMatchPattern(raw);
                if (r == null) {
                    warnings.add("無効なmatchesを無視(#" + (index + 1) + "): " + raw);
                } else {
                    baseMatches.add(r);
                }
            }
        }
        if (baseMatches.isEmpty()) {
            warnings.add("content_scripts #" + (index + 1) + " は有効なmatchesが無いためスキップしました");
            return null;
        }
        List<String> includeGlobs = readStrings(c.optJSONArray("include_globs"));
        if (includeGlobs.isEmpty()) {
            e.matches.addAll(baseMatches);
        } else if (baseMatches.size() * includeGlobs.size() > MAX_GLOB_COMBINATIONS) {
            warnings.add("include_globsの組合せが多すぎるため無視しました(#" + (index + 1) + ")");
            e.matches.addAll(baseMatches);
        } else {
            for (String bm : baseMatches) {
                for (String g : includeGlobs) {
                    String gr = PluginPatterns.toRegex(g);
                    String inter = gr == null ? null : PluginPatterns.intersect(bm, "/" + gr + "/");
                    if (inter != null) {
                        e.matches.add(inter);
                    }
                }
            }
            if (e.matches.isEmpty()) {
                e.matches.addAll(baseMatches);
            }
        }
        JSONArray ex = c.optJSONArray("exclude_matches");
        if (ex != null) {
            for (int i = 0; i < ex.length(); i++) {
                String r = PluginPatterns.fromMatchPattern(ex.optString(i, ""));
                if (r != null) {
                    e.excludes.add(r);
                }
            }
        }
        for (String g : readStrings(c.optJSONArray("exclude_globs"))) {
            if (PluginPatterns.toRegex(g) != null) {
                e.excludes.add(g);
            }
        }
        for (String js : readStrings(c.optJSONArray("js"))) {
            String p = PluginInstaller.refPath(js);
            if (p != null && files.containsKey(p)) {
                e.scripts.add(p);
            } else {
                warnings.add("jsが見つかりません: " + js);
            }
        }
        for (String css : readStrings(c.optJSONArray("css"))) {
            String p = PluginInstaller.refPath(css);
            if (p != null && files.containsKey(p)) {
                e.styles.add(p);
            } else {
                warnings.add("cssが見つかりません: " + css);
            }
        }
        if (!e.hasContent()) {
            warnings.add("content_scripts #" + (index + 1) + " は実行できるファイルが無いためスキップしました");
            return null;
        }
        return e;
    }

    private static List<String> readStrings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String pickIcon(JSONObject m, Map<String, byte[]> files) {
        List<String> candidates = new ArrayList<>();
        collectIcons(m.opt("icons"), candidates);
        for (String key : new String[]{"action", "browser_action", "page_action"}) {
            JSONObject o = m.optJSONObject(key);
            if (o != null) {
                collectIcons(o.opt("default_icon"), candidates);
            }
        }
        for (String ref : candidates) {
            String p = PluginInstaller.refPath(ref);
            if (p != null && files.containsKey(p) && p.toLowerCase(Locale.US).endsWith(".png")) {
                return p;
            }
        }
        return "";
    }

    private static void collectIcons(Object o, List<String> out) {
        if (o instanceof String) {
            out.add((String) o);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            List<Integer> sizes = new ArrayList<>();
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                try {
                    sizes.add(Integer.parseInt(it.next()));
                } catch (NumberFormatException ignored) {
                }
            }
            java.util.Collections.sort(sizes, java.util.Collections.reverseOrder());
            List<Integer> usable = new ArrayList<>();
            for (int s : sizes) {
                if (s <= 512) {
                    usable.add(s);
                }
            }
            usable.addAll(sizes);
            for (int s : usable) {
                String v = j.optString(String.valueOf(s), "");
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
    }

    public static final class Messages {
        private final JSONObject data;
        private final String extensionId;

        private Messages(JSONObject data, String extensionId) {
            this.data = data;
            this.extensionId = extensionId;
        }

        static Messages load(Map<String, byte[]> files, String defaultLocale) {
            Set<String> available = new HashSet<>();
            for (String name : files.keySet()) {
                if (name.startsWith("_locales/") && name.endsWith("/messages.json")) {
                    String loc = name.substring("_locales/".length(), name.length() - "/messages.json".length());
                    if (!loc.contains("/")) {
                        available.add(loc);
                    }
                }
            }
            String chosen = pickLocale(available, defaultLocale);
            JSONObject data = new JSONObject();
            if (chosen != null) {
                try {
                    data = new JSONObject(decode(files.get("_locales/" + chosen + "/messages.json")));
                } catch (Exception ignored) {
                }
            }
            return new Messages(data, "");
        }

        public static Messages of(String json, String extensionId) {
            try {
                return new Messages(new JSONObject(json), extensionId);
            } catch (Exception e) {
                return new Messages(new JSONObject(), extensionId);
            }
        }

        String resolve(String raw) {
            if (raw == null || raw.indexOf("__MSG_") < 0) {
                return raw == null ? "" : raw;
            }
            Matcher mt = MSG_REF.matcher(raw);
            StringBuffer sb = new StringBuffer();
            while (mt.find()) {
                String v = lookup(mt.group(1), null);
                mt.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
            }
            mt.appendTail(sb);
            return sb.toString();
        }

        public String lookup(String key, String[] subs) {
            if (key == null) {
                return null;
            }
            if (key.equals("@@extension_id")) {
                return extensionId;
            }
            if (key.equals("@@ui_locale")) {
                return Locale.getDefault().toString();
            }
            if (key.equals("@@bidi_dir")) {
                return "ltr";
            }
            JSONObject entry = data.optJSONObject(key);
            if (entry == null) {
                String lower = key.toLowerCase(Locale.US);
                java.util.Iterator<String> it = data.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.toLowerCase(Locale.US).equals(lower)) {
                        entry = data.optJSONObject(k);
                        break;
                    }
                }
            }
            if (entry == null) {
                return null;
            }
            String message = entry.optString("message", "");
            JSONObject placeholders = entry.optJSONObject("placeholders");
            StringBuilder sb = new StringBuilder();
            int n = message.length();
            int i = 0;
            while (i < n) {
                char c = message.charAt(i);
                if (c != '$') {
                    sb.append(c);
                    i++;
                    continue;
                }
                if (i + 1 < n && message.charAt(i + 1) == '$') {
                    sb.append('$');
                    i += 2;
                    continue;
                }
                if (i + 1 < n && Character.isDigit(message.charAt(i + 1))) {
                    int idx = message.charAt(i + 1) - '1';
                    if (subs != null && idx >= 0 && idx < subs.length && subs[idx] != null) {
                        sb.append(subs[idx]);
                    }
                    i += 2;
                    continue;
                }
                int end = message.indexOf('$', i + 1);
                if (end > i + 1 && placeholders != null) {
                    String name = message.substring(i + 1, end);
                    JSONObject ph = placeholders.optJSONObject(name);
                    if (ph == null) {
                        String lower = name.toLowerCase(Locale.US);
                        java.util.Iterator<String> it = placeholders.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (k.toLowerCase(Locale.US).equals(lower)) {
                                ph = placeholders.optJSONObject(k);
                                break;
                            }
                        }
                    }
                    if (ph != null) {
                        String content = ph.optString("content", "");
                        if (content.matches("\\$[1-9]") && subs != null) {
                            int idx = content.charAt(1) - '1';
                            if (idx < subs.length && subs[idx] != null) {
                                sb.append(subs[idx]);
                            }
                        } else {
                            sb.append(content);
                        }
                        i = end + 1;
                        continue;
                    }
                }
                sb.append(c);
                i++;
            }
            return sb.toString();
        }
    }

    public static String pickLocale(Set<String> available, String defaultLocale) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        Locale cur = Locale.getDefault();
        List<String> order = new ArrayList<>();
        String lang = cur.getLanguage();
        String country = cur.getCountry();
        if (!country.isEmpty()) {
            order.add(lang + "_" + country);
        }
        order.add(lang);
        if (defaultLocale != null && !defaultLocale.isEmpty()) {
            order.add(defaultLocale);
        }
        order.add("en");
        order.add("en_US");
        for (String want : order) {
            String w = want.replace('-', '_').toLowerCase(Locale.US);
            for (String have : available) {
                if (have.replace('-', '_').toLowerCase(Locale.US).equals(w)) {
                    return have;
                }
            }
        }
        List<String> sorted = new ArrayList<>(available);
        java.util.Collections.sort(sorted);
        return sorted.get(0);
    }
}
