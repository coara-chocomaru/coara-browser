package com.coara.browser.plugin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.coara.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PluginRuntime {
    private static final String RUNTIME_ASSET = "plugin_runtime.js";
    private static final String WATCHDOG_ASSET = "plugin_watchdog.js";

    private static final int MAX_COMMANDS = 32;

    private static String runtimeTemplate;
    private static String watchdogTemplate;
    private static final Map<String, String> PRELUDES = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class PluginCommand {
        public final String pluginId;
        public final String pluginTitle;
        public final String commandId;
        public final String title;

        PluginCommand(String pluginId, String pluginTitle, String commandId, String title) {
            this.pluginId = pluginId;
            this.pluginTitle = pluginTitle;
            this.commandId = commandId;
            this.title = title;
        }
    }

    private static final class ViewState {
        final List<ScriptHandler> handlers = new ArrayList<>();
        final Set<String> activeIds = new LinkedHashSet<>();
        int appliedVersion = -1;
        boolean startScriptsActive = false;
        boolean injectedForNavigation = false;
        long navigationStartedAt = 0L;
        final Map<String, String[]> commands = new LinkedHashMap<>();
    }

    private static final class ViewOpsImpl implements PluginViewOps {
        private final WeakReference<WebView> ref;
        private final ViewState state;

        ViewOpsImpl(WebView view, ViewState state) {
            this.ref = new WeakReference<>(view);
            this.state = state;
        }

        @Override
        public void registerCommand(String pluginId, String cmdId, String title) {
            synchronized (state.commands) {
                if (state.commands.size() >= MAX_COMMANDS && !state.commands.containsKey(cmdId)) {
                    return;
                }
                state.commands.put(cmdId, new String[]{pluginId, title});
            }
        }

        @Override
        public void unregisterCommand(String pluginId, String cmdId) {
            synchronized (state.commands) {
                String[] cur = state.commands.get(cmdId);
                if (cur != null && cur[0].equals(pluginId)) {
                    state.commands.remove(cmdId);
                }
            }
        }

        @Override
        public void openTab(final String url) {
            final WebView v = ref.get();
            if (v == null) {
                return;
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PluginHost host = hostOf(v.getContext());
                    if (host != null) {
                        host.onPluginOpenTab(url);
                    }
                }
            });
        }

        @Override
        public void notifyUser(final String title, final String text) {
            final WebView v = ref.get();
            if (v == null) {
                return;
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        String shown = text.isEmpty() ? title : title + ": " + text;
                        Toast.makeText(v.getContext().getApplicationContext(), shown, Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @Override
        public boolean copyText(final String text) {
            final WebView v = ref.get();
            if (v == null) {
                return false;
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("plugin", text));
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            return true;
        }
    }

    private static PluginHost hostOf(Context context) {
        Context c = context;
        int depth = 0;
        while (c != null && depth < 8) {
            if (c instanceof PluginHost) {
                return (PluginHost) c;
            }
            if (c instanceof ContextWrapper) {
                c = ((ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
            depth++;
        }
        return null;
    }

    public static List<PluginCommand> getCommands(WebView view) {
        List<PluginCommand> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        ViewState state = stateOf(view);
        if (state == null) {
            return out;
        }
        PluginManager manager = PluginManager.get(view.getContext());
        synchronized (state.commands) {
            for (Map.Entry<String, String[]> e : state.commands.entrySet()) {
                PluginInfo p = manager.get(e.getValue()[0]);
                if (p != null && p.enabled) {
                    out.add(new PluginCommand(p.id, p.title, e.getKey(), e.getValue()[1]));
                }
            }
        }
        return out;
    }

    public static void runCommand(WebView view, String commandId) {
        if (view == null || commandId == null) {
            return;
        }
        String q = JSONObject.quote(commandId);
        String js = "(function(i){try{var c=window.__coaraPlgCmd;var k=i.split(':')[0];if(c&&c[k])c[k](i);}catch(e){}})(" + q + ");";
        try {
            view.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    private PluginRuntime() {
    }

    public static void attach(Context context, WebView view) {
        if (context == null || view == null) {
            return;
        }
        try {
            PluginManager manager = PluginManager.get(context);
            manager.reloadIfChanged();
            ViewState state = new ViewState();
            view.addJavascriptInterface(manager.createBridge(new ViewOpsImpl(view, state)), PluginManager.BRIDGE_NAME);
            view.setTag(R.id.plugin_view_state, state);
            syncStartScripts(view, state, manager);
        } catch (Throwable t) {
            logFailure(context, "attach", t);
        }
    }

    public static void onPageStarted(WebView view, String url) {
        if (view == null) {
            return;
        }
        try {
            ViewState state = stateOf(view);
            if (state == null) {
                return;
            }
            PluginManager manager = PluginManager.get(view.getContext());
            manager.reloadIfChanged();
            if (state.appliedVersion != manager.version()) {
                syncStartScripts(view, state, manager);
            }
            state.injectedForNavigation = false;
            state.navigationStartedAt = System.currentTimeMillis();
            state.activeIds.clear();
            synchronized (state.commands) {
                state.commands.clear();
            }
            if (url != null) {
                List<String> ids = manager.matchingEnabledIds(url);
                if (!ids.isEmpty()) {
                    state.activeIds.addAll(ids);
                    manager.markInjected(ids);
                }
            }
        } catch (Throwable t) {
            logFailure(view.getContext(), "onPageStarted", t);
        }
    }

    public static void onPageCommitVisible(WebView view, String url) {
        injectFallback(view, url);
    }

    public static void onPageFinished(WebView view, String url) {
        injectFallback(view, url);
    }

    public static void onRenderProcessGone(WebView view, boolean didCrash) {
        if (view == null) {
            return;
        }
        try {
            ViewState state = stateOf(view);
            PluginManager manager = PluginManager.get(view.getContext());
            if (state == null || state.activeIds.isEmpty()) {
                return;
            }
            List<String> ids = new ArrayList<>(state.activeIds);
            long age = System.currentTimeMillis() - state.navigationStartedAt;
            if (didCrash) {
                manager.recordCrash(ids, "レンダラープロセスがクラッシュしました");
            } else {
                for (String id : ids) {
                    manager.log("W", id, "レンダラープロセスがシステムにより終了されました(ページ読み込みから" + (age / 1000) + "秒)");
                }
            }
            state.activeIds.clear();
        } catch (Throwable t) {
            logFailure(view.getContext(), "onRenderProcessGone", t);
        }
    }

    public static void onHostPause(Context context) {
        try {
            PluginManager.get(context).onHostPause();
        } catch (Throwable ignored) {
        }
    }

    private static void injectFallback(final WebView view, final String url) {
        if (view == null || url == null) {
            return;
        }
        try {
            final ViewState state = stateOf(view);
            if (state == null || state.startScriptsActive || state.injectedForNavigation) {
                return;
            }
            if (!view.getSettings().getJavaScriptEnabled()) {
                return;
            }
            final PluginManager manager = PluginManager.get(view.getContext());
            List<PluginInfo> enabled = manager.getEnabledPlugins();
            if (enabled.isEmpty()) {
                return;
            }
            state.injectedForNavigation = true;
            for (final PluginInfo info : enabled) {
                if (!info.isTarget(url)) {
                    continue;
                }
                List<String> scripts = buildPluginScripts(manager, info, url);
                final String id = info.id;
                for (String js : scripts) {
                    view.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value != null && !"null".equals(value)) {
                                return;
                            }
                            String current = null;
                            try {
                                current = view.getUrl();
                            } catch (Exception ignored) {
                            }
                            if (current != null && current.equals(url)) {
                                manager.recordError(id, "load",
                                        "プラグインのスクリプトを実行できませんでした(構文エラーの可能性)", "", url);
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            logFailure(view.getContext(), "injectFallback", t);
        }
    }

    private static void syncStartScripts(WebView view, ViewState state, PluginManager manager) {
        removeHandlers(state);
        state.startScriptsActive = false;
        state.appliedVersion = manager.version();
        boolean supported;
        try {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable t) {
            supported = false;
        }
        if (!supported) {
            return;
        }
        List<PluginInfo> enabled = manager.getEnabledPlugins();
        if (enabled.isEmpty()) {
            return;
        }
        Set<String> origins = Collections.singleton("*");
        try {
            String watchdog = buildWatchdogScript(manager, enabled);
            if (watchdog != null) {
                state.handlers.add(WebViewCompat.addDocumentStartJavaScript(view, watchdog, origins));
            }
            for (PluginInfo info : enabled) {
                for (String js : buildPluginScripts(manager, info, null)) {
                    state.handlers.add(WebViewCompat.addDocumentStartJavaScript(view, js, origins));
                }
            }
            state.startScriptsActive = true;
        } catch (Throwable t) {
            removeHandlers(state);
            state.startScriptsActive = false;
            manager.log("E", "-", "ドキュメント開始スクリプトの登録に失敗(フォールバックへ切替): " + t);
        }
    }

    private static void removeHandlers(ViewState state) {
        for (ScriptHandler h : state.handlers) {
            try {
                h.remove();
            } catch (Throwable ignored) {
            }
        }
        state.handlers.clear();
    }

    private static String buildWatchdogScript(PluginManager manager, List<PluginInfo> enabled) throws Exception {
        String template = loadTemplate(manager.context(), WATCHDOG_ASSET, true);
        if (template == null) {
            return null;
        }
        JSONArray expected = new JSONArray();
        for (PluginInfo p : enabled) {
            List<PluginEntry> entries = p.entries();
            for (int i = 0; i < entries.size(); i++) {
                PluginEntry e = entries.get(i);
                if (!e.hasContent()) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("key", manager.keyFor(p.id + "#" + i));
                o.put("allFrames", e.allFrames);
                o.put("matches", new JSONArray(PluginPatterns.toRegexList(PluginPatterns.effectiveMatches(e.matches))));
                o.put("excludes", new JSONArray(PluginPatterns.toRegexList(e.excludes)));
                expected.put(o);
            }
        }
        List<String[]> values = new ArrayList<>();
        values.add(new String[]{"%BRIDGE%", PluginManager.BRIDGE_NAME});
        values.add(new String[]{"%TOKEN%", manager.getToken()});
        values.add(new String[]{"%EXPECTED%", expected.toString()});
        return fill(template, values);
    }

    static List<String> buildPluginScripts(PluginManager manager, PluginInfo info, String url) {
        List<String> out = new ArrayList<>();
        List<PluginEntry> entries = info.entries();
        for (int i = 0; i < entries.size(); i++) {
            PluginEntry e = entries.get(i);
            if (url != null && !e.isTarget(url)) {
                continue;
            }
            String js = buildEntryScript(manager, info, e, i);
            if (js != null) {
                out.add(js);
            }
        }
        return out;
    }

    static String buildEntryScript(PluginManager manager, PluginInfo info, PluginEntry entry, int index) {
        try {
            String template = loadTemplate(manager.context(), RUNTIME_ASSET, false);
            if (template == null) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            for (String name : entry.scripts) {
                String src = manager.readText(info, name);
                if (src == null) {
                    manager.log("W", info.id, "スクリプトを読み込めません: " + name);
                    continue;
                }
                body.append(src).append("\n;\n");
            }
            JSONArray css = new JSONArray();
            for (String name : entry.styles) {
                String text = manager.readText(info, name);
                if (text == null) {
                    manager.log("W", info.id, "スタイルを読み込めません: " + name);
                    continue;
                }
                css.put(text);
            }
            if (body.length() == 0 && css.length() == 0) {
                return null;
            }
            JSONObject meta = new JSONObject();
            meta.put("id", info.id);
            meta.put("title", info.title);
            meta.put("version", info.version);
            meta.put("description", info.description);
            meta.put("author", info.author);
            meta.put("homepage", info.homepage);
            meta.put("format", info.format);
            meta.put("runAt", entry.runAt);
            meta.put("allFrames", entry.allFrames);
            meta.put("permissions", new JSONArray(info.permissions));
            meta.put("rawMatches", new JSONArray(entry.matches));
            meta.put("rawExcludes", new JSONArray(entry.excludes));
            meta.put("matches", new JSONArray(PluginPatterns.toRegexList(PluginPatterns.effectiveMatches(entry.matches))));
            meta.put("excludes", new JSONArray(PluginPatterns.toRegexList(entry.excludes)));

            String prelude = "";
            String kind = info.apiKind();
            if (kind.equals("gm")) {
                prelude = loadPrelude(manager.context(), "plugin_api_gm.js");
            } else if (kind.equals("webext")) {
                prelude = loadPrelude(manager.context(), "plugin_api_webext.js");
            }

            int idx = template.indexOf("%BODY%");
            if (idx < 0) {
                return null;
            }
            String head = template.substring(0, idx);
            String tail = template.substring(idx + "%BODY%".length());
            List<String[]> headValues = new ArrayList<>();
            headValues.add(new String[]{"%BRIDGE%", PluginManager.BRIDGE_NAME});
            headValues.add(new String[]{"%TOKEN%", manager.getToken()});
            headValues.add(new String[]{"%META%", meta.toString()});
            headValues.add(new String[]{"%CSS%", css.toString()});
            headValues.add(new String[]{"%KEY%", manager.keyFor(info.id + "#" + index)});
            headValues.add(new String[]{"%PKEY%", manager.keyFor(info.id)});
            headValues.add(new String[]{"%PRELUDE%", prelude});
            List<String[]> tailValues = new ArrayList<>();
            tailValues.add(new String[]{"%ID%", info.id + "/" + index});
            return fill(head, headValues) + body + fill(tail, tailValues);
        } catch (Throwable t) {
            manager.log("E", info.id, "スクリプト生成に失敗: " + t);
            return null;
        }
    }

    private static synchronized String loadPrelude(Context context, String asset) {
        String cached = PRELUDES.get(asset);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = context.getAssets().open(asset)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            PRELUDES.put(asset, text);
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    private static String fill(String template, List<String[]> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '%') {
                boolean replaced = false;
                for (String[] kv : values) {
                    if (template.startsWith(kv[0], i)) {
                        out.append(kv[1]);
                        i += kv[0].length();
                        replaced = true;
                        break;
                    }
                }
                if (replaced) {
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static synchronized String loadTemplate(Context context, String asset, boolean watchdog) {
        String cached = watchdog ? watchdogTemplate : runtimeTemplate;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = context.getAssets().open(asset)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (watchdog) {
                watchdogTemplate = text;
            } else {
                runtimeTemplate = text;
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static ViewState stateOf(WebView view) {
        Object o = view.getTag(R.id.plugin_view_state);
        return o instanceof ViewState ? (ViewState) o : null;
    }

    private static void logFailure(Context context, String where, Throwable t) {
        try {
            PluginManager.get(context).log("E", "-", "PluginRuntime." + where + " で例外: " + t);
        } catch (Throwable ignored) {
        }
    }
}
