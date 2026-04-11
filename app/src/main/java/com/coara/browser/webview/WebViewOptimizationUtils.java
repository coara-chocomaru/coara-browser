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
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(false);
        settings.setTextZoom(100);
        settings.setDisplayZoomControls(false);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setOffscreenPreRaster(true);
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                    settings,
                    darkModeEnabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF
            );
        }
    }

    public static void applyCombinedOptimizations(WebView webView) {
        String js = "javascript:(function(){" +
                "var animatedElements=document.querySelectorAll('.animated,.transition');" +
                "animatedElements.forEach(function(el){" +
                "if(!el.style.transform){el.style.transform='translateZ(0)';}" +
                "if(!el.style.willChange){el.style.willChange='transform,opacity';}" +
                "});" +
                "var fixedElements=document.querySelectorAll('.fixed');" +
                "fixedElements.forEach(function(el){" +
                "if(el.style.position!=='fixed'){el.style.position='fixed';}" +
                "});" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public static void injectLazyLoading(WebView webView) {
        String js = "javascript:(function(){" +
                "var placeholder='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';" +
                "var images=document.querySelectorAll('img[src^=\"https://i.ytimg.com/\"]:not([data-lazy-loaded])');" +
                "if(images.length===0)return;" +
                "images.forEach(function(img){" +
                "img.setAttribute('data-lazy-loaded','true');" +
                "if(img.hasAttribute('src')){" +
                "img.setAttribute('data-src',img.src);" +
                "img.src=placeholder;" +
                "img.style.opacity='0';" +
                "img.style.transition='opacity 0.3s';" +
                "if(!img.style.transform){img.style.transform='translateZ(0)';}" +
                "}" +
                "});" +
                "if('IntersectionObserver' in window){" +
                "var observer=new IntersectionObserver(function(entries){" +
                "entries.forEach(function(entry){" +
                "if(entry.isIntersecting){" +
                "var img=entry.target;" +
                "if(img.dataset.src){" +
                "img.src=img.dataset.src;" +
                "img.removeAttribute('data-src');" +
                "img.onload=function(){img.style.opacity='1';};" +
                "img.onerror=function(){console.warn('Image load failed: '+img.src);};" +
                "}" +
                "observer.unobserve(img);" +
                "}" +
                "});" +
                "},{root:null,rootMargin:'0px',threshold:0.1});" +
                "images.forEach(function(img){observer.observe(img);});" +
                "}else{" +
                "var loadImagesOnScroll=function(){" +
                "images.forEach(function(img){" +
                "if(img.dataset.src&&isElementInViewport(img)){" +
                "img.src=img.dataset.src;" +
                "img.removeAttribute('data-src');" +
                "img.onload=function(){img.style.opacity='1';};" +
                "img.onerror=function(){console.warn('Image load failed: '+img.src);};" +
                "}" +
                "});" +
                "};" +
                "var isElementInViewport=function(el){" +
                "var rect=el.getBoundingClientRect();" +
                "return(rect.top>=0&&rect.left>=0&&rect.bottom<=(window.innerHeight||document.documentElement.clientHeight)&&rect.right<=(window.innerWidth||document.documentElement.clientWidth));" +
                "};" +
                "window.addEventListener('scroll',loadImagesOnScroll);" +
                "window.addEventListener('resize',loadImagesOnScroll);" +
                "window.addEventListener('load',loadImagesOnScroll);" +
                "loadImagesOnScroll();" +
                "}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }
}
