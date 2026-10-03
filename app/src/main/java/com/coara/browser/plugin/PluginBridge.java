package com.coara.browser.plugin;

import android.webkit.JavascriptInterface;

public final class PluginBridge {
    private final PluginManager manager;
    private final PluginViewOps ops;

    PluginBridge(PluginManager manager, PluginViewOps ops) {
        this.manager = manager;
        this.ops = ops;
    }

    @JavascriptInterface
    public String invoke(String token, String id, String op, String a, String b, String c) {
        try {
            if (!manager.checkToken(token) || id == null || op == null) {
                return null;
            }
            return manager.handleBridge(ops, id, op, a, b, c);
        } catch (Throwable t) {
            return null;
        }
    }
}
