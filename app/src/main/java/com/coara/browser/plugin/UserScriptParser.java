package com.coara.browser.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UserScriptParser {
    public static final class Meta {
        public String name = "";
        public String namespace = "";
        public String version = "";
        public String description = "";
        public String author = "";
        public String icon = "";
        public String homepage = "";
        public String runAt = "";
        public boolean noFrames = false;
        public final List<String> matches = new ArrayList<>();
        public final List<String> includes = new ArrayList<>();
        public final List<String> excludes = new ArrayList<>();
        public final List<String> excludeMatches = new ArrayList<>();
        public final List<String> grants = new ArrayList<>();
        public final List<String> requires = new ArrayList<>();
        public final Map<String, String> resources = new LinkedHashMap<>();
        public final List<String> connects = new ArrayList<>();
    }

    private UserScriptParser() {
    }

    public static boolean hasMetaBlock(String src) {
        return src != null && findBlock(src) != null;
    }

    private static String findBlock(String src) {
        int a = src.indexOf("==UserScript==");
        if (a < 0) {
            return null;
        }
        int b = src.indexOf("==/UserScript==", a);
        if (b < 0) {
            return null;
        }
        return src.substring(a + "==UserScript==".length(), b);
    }

    public static Meta parse(String src) {
        Meta m = new Meta();
        String block = findBlock(src);
        if (block == null) {
            return m;
        }
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.US);
        String localizedName = null;
        String localizedDesc = null;
        for (String raw : block.split("\r?\n")) {
            String line = raw.trim();
            while (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                line = line.startsWith("/*") || line.startsWith("//") ? line.substring(2).trim() : line.substring(1).trim();
            }
            if (!line.startsWith("@")) {
                continue;
            }
            int sp = 1;
            while (sp < line.length() && !Character.isWhitespace(line.charAt(sp))) {
                sp++;
            }
            String key = line.substring(1, sp).toLowerCase(Locale.US);
            String value = line.substring(sp).trim();
            switch (key) {
                case "name":
                    if (m.name.isEmpty()) {
                        m.name = value;
                    }
                    break;
                case "namespace":
                    m.namespace = value;
                    break;
                case "version":
                    m.version = value;
                    break;
                case "description":
                    if (m.description.isEmpty()) {
                        m.description = value;
                    }
                    break;
                case "author":
                    m.author = value;
                    break;
                case "icon":
                case "iconurl":
                case "defaulticon":
                    if (m.icon.isEmpty()) {
                        m.icon = value;
                    }
                    break;
                case "homepage":
                case "homepageurl":
                case "website":
                case "source":
                    if (m.homepage.isEmpty()) {
                        m.homepage = value;
                    }
                    break;
                case "run-at":
                    m.runAt = value;
                    break;
                case "noframes":
                    m.noFrames = true;
                    break;
                case "match":
                    addIfNotEmpty(m.matches, value);
                    break;
                case "include":
                    addIfNotEmpty(m.includes, value);
                    break;
                case "exclude":
                    addIfNotEmpty(m.excludes, value);
                    break;
                case "exclude-match":
                    addIfNotEmpty(m.excludeMatches, value);
                    break;
                case "grant":
                    addIfNotEmpty(m.grants, value);
                    break;
                case "require":
                    addIfNotEmpty(m.requires, value);
                    break;
                case "connect":
                    addIfNotEmpty(m.connects, value.toLowerCase(Locale.US));
                    break;
                case "resource": {
                    String[] kv = value.split("\\s+", 2);
                    if (kv.length == 2 && !kv[0].isEmpty() && !kv[1].trim().isEmpty()) {
                        m.resources.put(kv[0], kv[1].trim());
                    }
                    break;
                }
                default:
                    if (key.startsWith("name:") && key.substring(5).startsWith(lang)) {
                        localizedName = value;
                    } else if (key.startsWith("description:") && key.substring(12).startsWith(lang)) {
                        localizedDesc = value;
                    }
                    break;
            }
        }
        if (localizedName != null && !localizedName.isEmpty()) {
            m.name = localizedName;
        }
        if (localizedDesc != null && !localizedDesc.isEmpty()) {
            m.description = localizedDesc;
        }
        return m;
    }

    private static void addIfNotEmpty(List<String> list, String v) {
        if (v != null && !v.trim().isEmpty()) {
            list.add(v.trim());
        }
    }

    public static String permissionForGrant(String grant) {
        if (grant == null) {
            return null;
        }
        String g = grant.trim();
        switch (g) {
            case "GM_xmlhttpRequest":
            case "GM.xmlHttpRequest":
            case "GM.xmlhttpRequest":
                return PluginInfo.PERM_XHR;
            case "GM_setClipboard":
            case "GM.setClipboard":
                return PluginInfo.PERM_CLIPBOARD;
            case "GM_notification":
            case "GM.notification":
                return PluginInfo.PERM_NOTIFICATION;
            case "GM_openInTab":
            case "GM.openInTab":
                return PluginInfo.PERM_OPENTAB;
            default:
                return null;
        }
    }

    public static void fillEntry(Meta m, PluginEntry entry, List<String> warnings) {
        for (String raw : m.matches) {
            String r = PluginPatterns.fromMatchPattern(raw);
            if (r == null) {
                warnings.add("無効な@matchを無視: " + raw);
            } else {
                entry.matches.add(r);
            }
        }
        for (String raw : m.includes) {
            String r = includeToPattern(raw);
            if (r == null) {
                warnings.add("無効な@includeを無視: " + raw);
            } else {
                entry.matches.add(r);
            }
        }
        for (String raw : m.excludes) {
            String r = includeToPattern(raw);
            if (r == null) {
                warnings.add("無効な@excludeを無視: " + raw);
            } else {
                entry.excludes.add(r);
            }
        }
        for (String raw : m.excludeMatches) {
            String r = PluginPatterns.fromMatchPattern(raw);
            if (r == null) {
                warnings.add("無効な@exclude-matchを無視: " + raw);
            } else {
                entry.excludes.add(r);
            }
        }
        if (entry.matches.isEmpty()) {
            entry.matches.addAll(PluginPatterns.DEFAULT_MATCHES);
            warnings.add("@match/@includeが無いためhttp/https全ページを対象にします");
        }
        String runAt = m.runAt.toLowerCase(Locale.US);
        if (runAt.equals("document-body")) {
            entry.runAt = PluginInfo.RUN_AT_END;
        } else {
            entry.runAt = PluginInfo.normalizeRunAt(runAt);
        }
        entry.allFrames = !m.noFrames;
    }

    static String includeToPattern(String raw) {
        String t = raw.trim();
        if (t.length() > 2 && t.startsWith("/")) {
            int end = t.lastIndexOf('/');
            if (end > 0) {
                String body = t.substring(1, end);
                String flags = t.substring(end + 1);
                if (flags.isEmpty() || flags.matches("[a-z]+")) {
                    return PluginPatterns.toRegex("/" + body + "/") == null ? null : "/" + body + "/";
                }
            }
        }
        if (t.equals("*")) {
            return "*";
        }
        if (t.toLowerCase(Locale.US).startsWith("http") || t.startsWith("*") || t.startsWith("file:")) {
            return PluginPatterns.toRegex(t) == null ? null : t;
        }
        return null;
    }
}
