package com.coara.browser.util;

import android.webkit.WebView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SpaStateManager {
    private static final int MAX_SCORE = 12;
    private static final int SPA_THRESHOLD = 6;
    private static final SpaStateManager INSTANCE = new SpaStateManager();

    private final Map<WebView, State> stateMap =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class State {
        int score;
        String lastUrl;
        int sameDocumentNavigations;
    }

    private SpaStateManager() {
    }

    public static SpaStateManager getInstance() {
        return INSTANCE;
    }

    public void setDetected(WebView webView, boolean isSpa) {
        if (webView == null) return;
        State state = getOrCreate(webView);
        synchronized (state) {
            state.score = isSpa ? Math.max(state.score, SPA_THRESHOLD) : Math.min(state.score, 0);
        }
    }

    public void setProbeScore(WebView webView, int score) {
        if (webView == null) return;
        State state = getOrCreate(webView);
        synchronized (state) {
            state.score = Math.max(state.score, clamp(score));
        }
    }

    public void addSignal(WebView webView, int weight) {
        if (webView == null || weight == 0) return;
        State state = getOrCreate(webView);
        synchronized (state) {
            state.score = clamp(state.score + weight);
        }
    }

    public void recordSameDocumentNavigation(WebView webView, String url, boolean pageInitiated) {
        if (webView == null) return;
        State state = getOrCreate(webView);
        synchronized (state) {
            state.lastUrl = url;
            state.sameDocumentNavigations++;
            if (pageInitiated && state.sameDocumentNavigations == 1) {
                state.score = clamp(state.score + 1);
            }
        }
    }

    public boolean isSpa(WebView webView) {
        if (webView == null) return false;
        State state = stateMap.get(webView);
        if (state == null) return false;
        synchronized (state) {
            return state.score >= SPA_THRESHOLD;
        }
    }

    public int getScore(WebView webView) {
        if (webView == null) return 0;
        State state = stateMap.get(webView);
        if (state == null) return 0;
        synchronized (state) {
            return state.score;
        }
    }

    public String getLastUrl(WebView webView) {
        if (webView == null) return null;
        State state = stateMap.get(webView);
        if (state == null) return null;
        synchronized (state) {
            return state.lastUrl;
        }
    }

    public void clearState(WebView webView) {
        if (webView != null) stateMap.remove(webView);
    }

    private State getOrCreate(WebView webView) {
        State state = stateMap.get(webView);
        if (state != null) return state;
        synchronized (stateMap) {
            state = stateMap.get(webView);
            if (state == null) {
                state = new State();
                stateMap.put(webView, state);
            }
            return state;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_SCORE, value));
    }
}
