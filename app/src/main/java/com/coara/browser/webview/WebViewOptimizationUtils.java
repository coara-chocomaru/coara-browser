package com.coara.browser.webview;

import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

public final class WebViewOptimizationUtils {
    private WebViewOptimizationUtils() {}

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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
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
    }

    public static void applyCombinedOptimizations(WebView webView) {
        String js = "javascript:(function(){"
                + "try{if(window.__coaraCombinedOptimized)return;window.__coaraCombinedOptimized=true;}catch(e){}"
                + "var run=function(){"
                + "try{"
                + "var anim=document.querySelectorAll('.animated,.transition,[class*=\"animate\"],[class*=\"transition\"]');"
                + "for(var i=0;i<anim.length;i++){"
                + "var s=anim[i].style;"
                + "if(!s.transform)s.transform='translateZ(0)';"
                + "if(!s.willChange)s.willChange='transform,opacity';"
                + "}"
                + "var fxd=document.querySelectorAll('[style*=\"position:fixed\"],[style*=\"position: fixed\"]');"
                + "for(var j=0;j<fxd.length;j++){"
                + "var fs=fxd[j].style;"
                + "if(!fs.transform)fs.transform='translateZ(0)';"
                + "if(!fs.willChange)fs.willChange='transform';"
                + "}"
                + "if(document.querySelectorAll('*').length<2000){"
                + "var nodes=document.querySelectorAll('header,nav,footer,[class*=\"sticky\"],[class*=\"fixed\"],[class*=\"navbar\"],[class*=\"header\"],[class*=\"toolbar\"]');"
                + "for(var k=0;k<nodes.length;k++){"
                + "try{"
                + "var pos=window.getComputedStyle(nodes[k]).position;"
                + "if(pos==='fixed'||pos==='sticky'){"
                + "var ks=nodes[k].style;"
                + "if(!ks.transform)ks.transform='translateZ(0)';"
                + "if(!ks.willChange)ks.willChange='transform';"
                + "}"
                + "}catch(e){}"
                + "}"
                + "}"
                + "var scs=document.querySelectorAll('[style*=\"overflow:auto\"],[style*=\"overflow: auto\"],[style*=\"overflow:scroll\"],[style*=\"overflow: scroll\"],[style*=\"overflow-y:auto\"],[style*=\"overflow-y: auto\"],[style*=\"overflow-y:scroll\"],[style*=\"overflow-y: scroll\"]');"
                + "for(var m=0;m<scs.length;m++){"
                + "if(!scs[m].style.willChange)scs[m].style.willChange='scroll-position';"
                + "}"
                + "}catch(err){}"
                + "};"
                + "if(window.requestIdleCallback){requestIdleCallback(run,{timeout:250});}else{requestAnimationFrame(run);}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    public static void injectSpaProbe(WebView webView) {
        String js = "javascript:(function(){"
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
}
