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



    public static void applyCombinedOptimizations(WebView webView) {
        if (webView == null) return;
        String js = "(function(){" +
                "try{" +
                "if(window.__coaraContentHintsApplied)return;" +
                "window.__coaraContentHintsApplied=true;" +
                "var apply=function(){" +
                "var images=document.images;" +
                "for(var i=0;i<images.length;i++){" +
                "var image=images[i];" +
                "if(!image.getAttribute('decoding'))image.setAttribute('decoding','async');" +
                "if(!image.getAttribute('loading')){" +
                "try{" +
                "var rect=image.getBoundingClientRect();" +
                "if(rect.top>(window.innerHeight||document.documentElement.clientHeight)*1.5)image.setAttribute('loading','lazy');" +
                "}catch(e){}" +
                "}" +
                "}" +
                "};" +
                "if(window.requestIdleCallback)requestIdleCallback(apply,{timeout:400});" +
                "else if(window.requestAnimationFrame)requestAnimationFrame(apply);" +
                "else setTimeout(apply,0);" +
                "}catch(e){}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public static void injectSpaProbe(WebView webView) {
        if (webView == null) return;
        String js = "(function(){" +
                "var score=0;" +
                "var add=function(n){score=Math.min(12,score+n);};" +
                "try{" +
                "if(document.querySelector('[data-reactroot],#__next,[data-v-app],#__nuxt,[ng-version]'))add(4);" +
                "if(window.__NEXT_DATA__||window.__NUXT__||window.__INITIAL_STATE__||window.__APOLLO_STATE__)add(3);" +
                "var root=document.getElementById('root')||document.getElementById('app')||document.getElementById('__next')||document.getElementById('__nuxt');" +
                "if(root&&root.childElementCount>=2)add(2);" +
                "var scripts=document.querySelectorAll('script[src]'),bundles=0;" +
                "for(var i=0;i<scripts.length;i++){var src=(scripts[i].getAttribute('src')||'').toLowerCase();if(src.indexOf('chunk')!==-1||src.indexOf('bundle')!==-1||src.indexOf('webpack')!==-1||src.indexOf('/_next/static/')!==-1||src.indexOf('/_nuxt/')!==-1||src.indexOf('vite')!==-1)bundles++;}" +
                "if(bundles>=2)add(2);" +
                "var anchors=document.querySelectorAll('a[href]'),sameOrigin=0,internalPath=0;" +
                "for(var j=0;j<anchors.length&&j<80;j++){try{var a=new URL(anchors[j].href,location.href);if(a.origin===location.origin){sameOrigin++;if(a.pathname!==location.pathname)internalPath++;}}catch(e){}}" +
                "if(sameOrigin>=5&&internalPath>=3)add(1);" +
                "var hash=location.hash||'';" +
                "if(/^#(?:!|\\/|[a-z0-9_-]{2,}\\/)/i.test(hash))add(4);" +
                "if('serviceWorker' in navigator)add(1);" +
                "}catch(e){}" +
                "try{" +
                "if(window.AndroidBridge)AndroidBridge.onSpaScore(score);" +
                "if(!window.__coaraHistoryHooked){" +
                "window.__coaraHistoryHooked=true;" +
                "var notify=function(kind){try{if(window.AndroidBridge){AndroidBridge.onSpaSignal(kind);AndroidBridge.onUrlChange(location.href);}}catch(e){}};" +
                "var push=history.pushState;" +
                "history.pushState=function(){var result=push.apply(this,arguments);notify(5);return result;};" +
                "var replace=history.replaceState;" +
                "history.replaceState=function(){var result=replace.apply(this,arguments);notify(4);return result;};" +
                "addEventListener('popstate',function(){notify(1);},false);" +
                "addEventListener('hashchange',function(){notify(1);},false);" +
                "}" +
                "}catch(e){}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public static void injectLazyLoading(WebView webView) {
        if (webView == null) return;
        String js = "(function(){" +
                "try{" +
                "var images=document.querySelectorAll(\"img[src*='i.ytimg.com']\");" +
                "for(var i=0;i<images.length;i++){" +
                "var image=images[i];" +
                "if(!image.getAttribute('loading'))image.setAttribute('loading','lazy');" +
                "if(!image.getAttribute('decoding'))image.setAttribute('decoding','async');" +
                "}" +
                "}catch(e){}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public static String sanitizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return userAgent;
        }
        String sanitized = userAgent.replace("; wv)", ")");
        sanitized = sanitized.replace(" Version/4.0", "");
        return sanitized;
    }
}
