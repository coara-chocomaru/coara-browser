package com.coara.browser.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginInfo {
    public static final String RUN_AT_START = "document_start";
    public static final String RUN_AT_END = "document_end";
    public static final String RUN_AT_IDLE = "document_idle";

    public static final String FORMAT_COARA = "coara";
    public static final String FORMAT_USERSCRIPT = "userscript";
    public static final String FORMAT_WEBEXT = "webext";
    public static final String FORMAT_USERSTYLE = "userstyle";

    public static final String PERM_XHR = "xhr";
    public static final String PERM_CLIPBOARD = "clipboard";
    public static final String PERM_NOTIFICATION = "notification";
    public static final String PERM_OPENTAB = "opentab";

    public String id = "";
    public String title = "";
    public String version = "";
    public String author = "";
    public String description = "";
    public String runAt = RUN_AT_END;
    public String icon = "";
    public boolean allFrames = false;
    public boolean enabled = true;
    public final List<String> matches = new ArrayList<>();
    public final List<String> excludes = new ArrayList<>();
    public final List<String> scripts = new ArrayList<>();
    public final List<String> styles = new ArrayList<>();
    public final List<String> resources = new ArrayList<>();
    public final List<String> assets = new ArrayList<>();
    public final List<String> permissions = new ArrayList<>();
    public final List<String> connects = new ArrayList<>();
    public final List<PluginEntry> entries = new ArrayList<>();
    public final Map<String, String> resourceMap = new LinkedHashMap<>();
    public String format = FORMAT_COARA;
    public String defaultLocale = "";
    public String homepage = "";
    public long installedAt = 0L;
    public long lastErrorAt = 0L;
    public long disabledAt = 0L;
    public int errorCount = 0;
    public int crashCount = 0;
    public int failStreak = 0;
    public String lastError = "";
    public String disabledReason = "";

    public static String normalizeRunAt(String raw) {
        if (raw == null) {
            return RUN_AT_END;
        }
        String v = raw.trim().toLowerCase(Locale.US);
        if (v.contains("start")) {
            return RUN_AT_START;
        }
        if (v.contains("idle")) {
            return RUN_AT_IDLE;
        }
        return RUN_AT_END;
    }

    public static String normalizeFormat(String raw) {
        if (FORMAT_USERSCRIPT.equals(raw) || FORMAT_WEBEXT.equals(raw) || FORMAT_USERSTYLE.equals(raw)) {
            return raw;
        }
        return FORMAT_COARA;
    }

    public boolean hasPermission(String perm) {
        return permissions.contains(perm);
    }

    public String apiKind() {
        if (FORMAT_USERSCRIPT.equals(format)) {
            return "gm";
        }
        if (FORMAT_WEBEXT.equals(format)) {
            return "webext";
        }
        return "coara";
    }

    public List<PluginEntry> entries() {
        if (!entries.isEmpty()) {
            return entries;
        }
        List<PluginEntry> one = new ArrayList<>();
        PluginEntry e = new PluginEntry();
        e.runAt = runAt;
        e.allFrames = allFrames;
        e.matches.addAll(matches);
        e.excludes.addAll(excludes);
        e.scripts.addAll(scripts);
        e.styles.addAll(styles);
        one.add(e);
        return one;
    }

    public boolean isTarget(String url) {
        for (PluginEntry e : entries()) {
            if (e.isTarget(url)) {
                return true;
            }
        }
        return false;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("version", version);
        o.put("author", author);
        o.put("description", description);
        o.put("runAt", runAt);
        o.put("icon", icon);
        o.put("allFrames", allFrames);
        o.put("enabled", enabled);
        o.put("matches", toArray(matches));
        o.put("excludes", toArray(excludes));
        o.put("scripts", toArray(scripts));
        o.put("styles", toArray(styles));
        o.put("resources", toArray(resources));
        o.put("installedAt", installedAt);
        o.put("lastErrorAt", lastErrorAt);
        o.put("disabledAt", disabledAt);
        o.put("errorCount", errorCount);
        o.put("crashCount", crashCount);
        o.put("failStreak", failStreak);
        o.put("lastError", lastError);
        o.put("disabledReason", disabledReason);
        o.put("format", format);
        o.put("defaultLocale", defaultLocale);
        o.put("homepage", homepage);
        o.put("assets", toArray(assets));
        o.put("permissions", toArray(permissions));
        o.put("connects", toArray(connects));
        JSONArray ea = new JSONArray();
        for (PluginEntry e : entries) {
            ea.put(e.toJson());
        }
        o.put("entries", ea);
        JSONObject rm = new JSONObject();
        for (Map.Entry<String, String> kv : resourceMap.entrySet()) {
            rm.put(kv.getKey(), kv.getValue());
        }
        o.put("resourceMap", rm);
        return o;
    }

    public static PluginInfo fromJson(JSONObject o) {
        PluginInfo p = new PluginInfo();
        p.id = o.optString("id", "");
        p.title = o.optString("title", "");
        p.version = o.optString("version", "");
        p.author = o.optString("author", "");
        p.description = o.optString("description", "");
        p.runAt = normalizeRunAt(o.optString("runAt", RUN_AT_END));
        p.icon = o.optString("icon", "");
        p.allFrames = o.optBoolean("allFrames", false);
        p.enabled = o.optBoolean("enabled", true);
        readArray(o.optJSONArray("matches"), p.matches);
        readArray(o.optJSONArray("excludes"), p.excludes);
        readArray(o.optJSONArray("scripts"), p.scripts);
        readArray(o.optJSONArray("styles"), p.styles);
        readArray(o.optJSONArray("resources"), p.resources);
        p.installedAt = o.optLong("installedAt", 0L);
        p.lastErrorAt = o.optLong("lastErrorAt", 0L);
        p.disabledAt = o.optLong("disabledAt", 0L);
        p.errorCount = o.optInt("errorCount", 0);
        p.crashCount = o.optInt("crashCount", 0);
        p.failStreak = o.optInt("failStreak", 0);
        p.lastError = o.optString("lastError", "");
        p.disabledReason = o.optString("disabledReason", "");
        p.format = normalizeFormat(o.optString("format", FORMAT_COARA));
        p.defaultLocale = o.optString("defaultLocale", "");
        p.homepage = o.optString("homepage", "");
        readArray(o.optJSONArray("assets"), p.assets);
        readArray(o.optJSONArray("permissions"), p.permissions);
        readArray(o.optJSONArray("connects"), p.connects);
        JSONArray ea = o.optJSONArray("entries");
        if (ea != null) {
            for (int i = 0; i < ea.length(); i++) {
                JSONObject eo = ea.optJSONObject(i);
                if (eo != null) {
                    p.entries.add(PluginEntry.fromJson(eo));
                }
            }
        }
        JSONObject rm = o.optJSONObject("resourceMap");
        if (rm != null) {
            Iterator<String> it = rm.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = rm.optString(k, "");
                if (!v.isEmpty()) {
                    p.resourceMap.put(k, v);
                }
            }
        }
        return p;
    }

    public PluginInfo copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            PluginInfo p = new PluginInfo();
            p.id = id;
            p.title = title;
            return p;
        }
    }

    private static JSONArray toArray(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) {
            a.put(s);
        }
        return a;
    }

    static void readArray(JSONArray a, List<String> out) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s != null) {
                out.add(s);
            }
        }
    }
}
