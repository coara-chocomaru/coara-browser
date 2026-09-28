package com.coara.browser.webview;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

public final class WebViewOptimizationUtils {

    private WebViewOptimizationUtils() {
    }

    @SuppressWarnings("deprecation")
    public static void applyOptimizedSettings(WebSettings settings, boolean darkModeEnabled) {
        applyOptimizedSettings(settings, darkModeEnabled, true);
    }

    @SuppressWarnings("deprecation")
    public static void applyOptimizedSettings(WebSettings settings, boolean darkModeEnabled, boolean javascriptEnabled) {
        settings.setJavaScriptEnabled(javascriptEnabled);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        settings.setDisplayZoomControls(false);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setNeedInitialFocus(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                    settings,
                    darkModeEnabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF
            );
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            );
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        }
    }


    public static void injectSpaProbe(WebView webView) {
        if (webView == null) return;
        String js = "(function(){" +
                "var score=0;" +
                "var add=function(n){score=Math.min(12,score+n);};" +
                "try{" +
                "var framework=!!document.querySelector('[data-reactroot],#__next,[data-v-app],#__nuxt,[ng-version],astro-island');" +
                "var state=!!(window.__NEXT_DATA__||window.__NUXT__||window.__INITIAL_STATE__||window.__APOLLO_STATE__||window.__PREACT_CLI_DATA__);" +
                "var root=document.getElementById('root')||document.getElementById('app')||document.getElementById('__next')||document.getElementById('__nuxt');" +
                "var rootSize=root?root.childElementCount:0;" +
                "var scripts=document.querySelectorAll('script[src]'),bundles=0;" +
                "for(var i=0;i<scripts.length;i++){var src=(scripts[i].getAttribute('src')||'').toLowerCase();if(src.indexOf('/_next/')!==-1||src.indexOf('/_nuxt/')!==-1||src.indexOf('webpack')!==-1||src.indexOf('chunk')!==-1||src.indexOf('bundle')!==-1||src.indexOf('vite')!==-1)bundles++;}" +
                "var anchors=document.querySelectorAll('a[href]'),sameOrigin=0,internalPath=0;" +
                "for(var j=0;j<anchors.length&&j<60;j++){try{var a=new URL(anchors[j].href,location.href);if(a.origin===location.origin){sameOrigin++;if(a.pathname!==location.pathname)internalPath++;}}catch(e){}}" +
                "var hash=location.hash||'';" +
                "var hashRoute=/^#(?:!|\\/|[a-z0-9_-]{2,}\\/)/i.test(hash);" +
                "if(framework)add(3);" +
                "if(state)add(3);" +
                "if(root&&rootSize>=2)add(2);" +
                "if(bundles>=2)add(1);" +
                "if(sameOrigin>=6&&internalPath>=3)add(1);" +
                "if(hashRoute)add(3);" +
                "if((framework||state)&&(root&&rootSize>=2)&&bundles>=1)add(2);" +
                "}catch(e){}" +
                "try{if(window.AndroidBridge)AndroidBridge.onSpaScore(score);}catch(e){}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }}
