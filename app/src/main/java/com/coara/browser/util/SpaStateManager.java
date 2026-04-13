package com.coara.browser.util;

import android.webkit.WebView;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.Map;


public final class SpaStateManager {

    private static final SpaStateManager INSTANCE = new SpaStateManager();

 
    private final Map<WebView, Boolean> spaMap =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SpaStateManager() {}

    public static SpaStateManager getInstance() {
        return INSTANCE;
    }


    public void setDetected(WebView wv, boolean isSpa) {
        if (wv != null) spaMap.put(wv, isSpa);
    }


    public boolean isSpa(WebView wv) {
        if (wv == null) return false;
        Boolean b = spaMap.get(wv);
        return b != null && b;
    }


    public void clearState(WebView wv) {
        if (wv != null) spaMap.remove(wv);
    }
}
