package com.coara.browser.plugin;

import android.webkit.JavascriptInterface;

public final class PluginBridge {
    private final PluginManager manager;

    PluginBridge(PluginManager manager) {
        this.manager = manager;
    }

    @JavascriptInterface
    public String invoke(String token, String id, String op, String a, String b, String c) {
        try {
            if (!manager.checkToken(token) || id == null || op == null) {
                return null;
            }
            return manager.handleBridge(id, op, a, b, c);
        } catch (Throwable t) {
            return null;
        }
    }
}
