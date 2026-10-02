package com.coara.browser.plugin;

import android.content.Context;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.coara.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PluginRuntime {
    private static final String RUNTIME_ASSET = "plugin_runtime.js";
    private static final String WATCHDOG_ASSET = "plugin_watchdog.js";

    private static String runtimeTemplate;
    private static String watchdogTemplate;

    private static final class ViewState {
        final List<ScriptHandler> handlers = new ArrayList<>();
        final Set<String> activeIds = new LinkedHashSet<>();
        int appliedVersion = -1;
        boolean startScriptsActive = false;
        boolean injectedForNavigation = false;
        long navigationStartedAt = 0L;
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
            view.addJavascriptInterface(manager.bridge(), PluginManager.BRIDGE_NAME);
            ViewState state = new ViewState();
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
                if (!PluginPatterns.isTarget(info, url)) {
                    continue;
                }
                String js = buildPluginScript(manager, info);
                if (js == null) {
                    continue;
                }
                final String id = info.id;
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
                String js = buildPluginScript(manager, info);
                if (js != null) {
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
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("key", manager.keyFor(p.id));
            o.put("allFrames", p.allFrames);
            o.put("matches", new JSONArray(PluginPatterns.toRegexList(PluginPatterns.effectiveMatches(p.matches))));
            o.put("excludes", new JSONArray(PluginPatterns.toRegexList(p.excludes)));
            expected.put(o);
        }
        List<String[]> values = new ArrayList<>();
        values.add(new String[]{"%BRIDGE%", PluginManager.BRIDGE_NAME});
        values.add(new String[]{"%TOKEN%", manager.getToken()});
        values.add(new String[]{"%EXPECTED%", expected.toString()});
        return fill(template, values);
    }

    static String buildPluginScript(PluginManager manager, PluginInfo info) {
        try {
            String template = loadTemplate(manager.context(), RUNTIME_ASSET, false);
            if (template == null) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            for (String name : info.scripts) {
                String src = manager.readText(info, name);
                if (src == null) {
                    manager.log("W", info.id, "スクリプトを読み込めません: " + name);
                    continue;
                }
                body.append(src).append("\n;\n");
            }
            JSONArray css = new JSONArray();
            for (String name : info.styles) {
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
            meta.put("runAt", info.runAt);
            meta.put("allFrames", info.allFrames);
            meta.put("matches", new JSONArray(PluginPatterns.toRegexList(PluginPatterns.effectiveMatches(info.matches))));
            meta.put("excludes", new JSONArray(PluginPatterns.toRegexList(info.excludes)));

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
            headValues.add(new String[]{"%KEY%", manager.keyFor(info.id)});
            List<String[]> tailValues = new ArrayList<>();
            tailValues.add(new String[]{"%ID%", info.id});
            return fill(head, headValues) + body + fill(tail, tailValues);
        } catch (Throwable t) {
            manager.log("E", info.id, "スクリプト生成に失敗: " + t);
            return null;
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
