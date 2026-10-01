package com.coara.browser.webview;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

public final class WebViewOptimizationUtils {
    private WebViewOptimizationUtils() {}

    @SuppressWarnings("deprecation")
    public static void applyOptimizedSettings(WebSettings settings, boolean darkModeEnabled) {
        settings.setJavaScriptEnabled(true);
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(false);
        }

        settings.setNeedInitialFocus(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                    settings,
                    darkModeEnabled
                            ? WebSettingsCompat.FORCE_DARK_ON
                            : WebSettingsCompat.FORCE_DARK_OFF
            );
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            );
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, java.util.Collections.emptySet());
        }
    }

    public static void installDownloadHints(WebView webView) {
        if (webView == null) {
            return;
        }
        String js = "javascript:(function(){" +
                "try{" +
                "if(window.__coaraDownloadHintInstalled)return;" +
                "window.__coaraDownloadHintInstalled=true;" +
                "var seen={};" +
                "var remember=function(a,once){" +
                "try{" +
                "if(!a)return;" +
                "var n=a.getAttribute('download')||a.getAttribute('data-filename')||a.getAttribute('data-file-name')||a.getAttribute('data-download-name')||a.getAttribute('data-name');" +
                "var h=a.href||a.getAttribute('href')||a.getAttribute('data-url')||a.getAttribute('data-href')||a.getAttribute('data-download-url');" +
                "if(n&&h&&window.BlobDownloader){" +
                "if(once){var k=h+'|'+n;if(seen[k])return;seen[k]=1;}" +
                "window.BlobDownloader.rememberDownloadHint(h,n);" +
                "}" +
                "}catch(e){}" +
                "};" +
                "var scan=function(){" +
                "try{" +
                "var list=document.querySelectorAll('a[download]');" +
                "for(var i=0;i<list.length;i++)remember(list[i],true);" +
                "}catch(e){}" +
                "};" +
                "var timer=0;" +
                "var schedule=function(){" +
                "if(timer)return;" +
                "timer=setTimeout(function(){timer=0;scan();},300);" +
                "};" +
                "document.addEventListener('click',function(e){" +
                "try{" +
                "var a=e.target;" +
                "var depth=0;" +
                "while(a&&a.tagName!=='A'&&depth++<8)a=a.parentElement;" +
                "remember(a,false);" +
                "}catch(ex){}" +
                "},true);" +
                "scan();" +
                "if(window.MutationObserver){" +
                "new MutationObserver(schedule).observe(document.documentElement||document,{subtree:true,childList:true});" +
                "}" +
                "}catch(e){}" +
                "})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    public static void injectSpaProbe(WebView webView) {
        String js = "javascript:(function(){"
                + "if(window.__coaraSpaDetected===true){"
                + "try{AndroidBridge.onSpaDetected(true);}catch(qe){}"
                + "try{"
                + "if(!window.__coaraHistoryHooked){"
                + "window.__coaraHistoryHooked=true;"
                + "window.__coaraLastHref=location.href;"
                + "var nuc=function(force){"
                + "var href=location.href;"
                + "if(!force&&href===window.__coaraLastHref)return;"
                + "window.__coaraLastHref=href;"
                + "try{AndroidBridge.onUrlChange(href);}catch(ex){}"
                + "};"
                + "var ps=history.pushState;"
                + "history.pushState=function(){var r=ps.apply(history,arguments);nuc(true);return r;};"
                + "var rs=history.replaceState;"
                + "history.replaceState=function(){var r=rs.apply(history,arguments);nuc(true);return r;};"
                + "window.addEventListener('popstate',function(){nuc(true);});"
                + "window.addEventListener('hashchange',function(){nuc(true);});"
                + "}else{"
                + "try{AndroidBridge.onUrlChange(location.href);}catch(ex2){}"
                + "}"
                + "}catch(hookErr2){}"
                + "return;"
                + "}"
                + "var s=false;"
                + "try{"
                + "if(!s&&document.querySelector('[data-reactroot]'))s=true;"
                + "if(!s&&document.getElementById('__next'))s=true;"
                + "if(!s&&document.querySelector('[data-v-app]'))s=true;"
                + "if(!s&&document.getElementById('__nuxt'))s=true;"
                + "if(!s&&document.querySelector('[ng-version]'))s=true;"
                + "if(!s){"
                + "var root=document.getElementById('root')||document.getElementById('app');"
                + "if(root&&root.childElementCount>3)s=true;"
                + "}"
                + "if(!s){"
                + "var cs=document.querySelectorAll('canvas');"
                + "for(var i=0;i<cs.length;i++){"
                + "if(cs[i].offsetWidth>100&&cs[i].offsetHeight>100){s=true;break;}"
                + "}"
                + "}"
                + "if(!s){"
                + "try{"
                + "var bs=window.getComputedStyle(document.body);"
                + "if(bs.overflow==='hidden'||bs.overflowY==='hidden')s=true;"
                + "}catch(ce){}"
                + "}"
                + "if(!s){"
                + "var scr=document.querySelectorAll('script[src]'),bc=0;"
                + "for(var j=0;j<scr.length;j++){"
                + "var src=scr[j].getAttribute('src')||'';"
                + "if(src.indexOf('.chunk.js')>-1||src.indexOf('.bundle.js')>-1"
                + "||src.indexOf('/chunk.')>-1||src.indexOf('/bundle.')>-1)bc++;"
                + "if(bc>=2){s=true;break;}"
                + "}"
                + "}"
                + "if(!s){"
                + "try{"
                + "if(history.pushState.toString().indexOf('native code')===-1)s=true;"
                + "}catch(pe){}"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "if(!window.__coaraHistoryHooked){"
                + "window.__coaraHistoryHooked=true;"
                + "window.__coaraLastHref=location.href;"
                + "var notifyUrlChange=function(force){"
                + "var href=location.href;"
                + "if(!force&&href===window.__coaraLastHref)return;"
                + "window.__coaraLastHref=href;"
                + "try{AndroidBridge.onUrlChange(href);}catch(ex){}"
                + "};"
                + "var pushState=history.pushState;"
                + "history.pushState=function(){var r=pushState.apply(history,arguments);notifyUrlChange(true);return r;};"
                + "var replaceState=history.replaceState;"
                + "history.replaceState=function(){var r=replaceState.apply(history,arguments);notifyUrlChange(true);return r;};"
                + "window.addEventListener('popstate',function(){notifyUrlChange(true);});"
                + "window.addEventListener('hashchange',function(){notifyUrlChange(true);});"
                + "notifyUrlChange(true);"
                + "}else{"
                + "try{AndroidBridge.onUrlChange(location.href);}catch(ex2){}"
                + "}"
                + "}catch(hookErr){}"
                + "if('serviceWorker' in navigator){"
                + "try{"
                + "navigator.serviceWorker.getRegistrations().then(function(r){"
                + "if(r&&r.length>0){AndroidBridge.onSpaDetected(true);}"
                + "}).catch(function(){});"
                + "}catch(swe){}"
                + "}"
                + "if(s){try{window.__coaraSpaDetected=true;}catch(se){}}"
                + "AndroidBridge.onSpaDetected(s);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    public static void injectLazyLoading(WebView webView) {
        String js = "javascript:(function(){"
                + "try{if(window.__coaraLazyLoadingApplied)return;window.__coaraLazyLoadingApplied=true;}catch(e){}"
                + "var PH='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';"
                + "var imgs=document.querySelectorAll('img[src^=\"https://i.ytimg.com/\"]:not([data-lazy-loaded])');"
                + "if(imgs.length===0)return;"
                + "imgs.forEach(function(img){"
                + "img.setAttribute('data-lazy-loaded','true');"
                + "img.setAttribute('loading','lazy');"
                + "if(img.hasAttribute('src')){"
                + "img.setAttribute('data-src',img.src);"
                + "img.src=PH;"
                + "img.style.opacity='0';"
                + "img.style.transition='opacity 0.25s ease';"
                + "if(!img.style.transform)img.style.transform='translateZ(0)';"
                + "}"
                + "});"
                + "if('IntersectionObserver' in window){"
                + "var ob=new IntersectionObserver(function(entries){"
                + "entries.forEach(function(e){"
                + "if(e.isIntersecting){"
                + "var img=e.target;"
                + "if(img.dataset.src){"
                + "img.src=img.dataset.src;"
                + "img.removeAttribute('data-src');"
                + "img.onload=function(){img.style.opacity='1';};"
                + "img.onerror=function(){console.warn('Lazy:'+img.src);};"
                + "}"
                + "ob.unobserve(img);"
                + "}"
                + "});"
                + "},{root:null,rootMargin:'200px 0px',threshold:0});"
                + "imgs.forEach(function(img){ob.observe(img);});"
                + "}else{"
                + "function inVP(el){"
                + "var r=el.getBoundingClientRect();"
                + "return r.top<=(window.innerHeight||document.documentElement.clientHeight)+200&&r.bottom>=0;"
                + "}"
                + "function load(){"
                + "imgs.forEach(function(img){"
                + "if(img.dataset.src&&inVP(img)){"
                + "img.src=img.dataset.src;"
                + "img.removeAttribute('data-src');"
                + "img.onload=function(){img.style.opacity='1';};"
                + "img.onerror=function(){console.warn('Lazy:'+img.src);};"
                + "}"
                + "});"
                + "}"
                + "['scroll','resize','load'].forEach(function(ev){"
                + "window.addEventListener(ev,load,{passive:true});"
                + "});"
                + "load();"
                + "}"
                + "})();";
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
