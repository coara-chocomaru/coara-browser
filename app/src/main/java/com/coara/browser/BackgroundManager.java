package com.coara.browser;

import android.content.Context;
import android.content.SharedPreferences;

public class BackgroundManager {
    private static final String PREF_NAME = "AdvancedBrowserPrefs";
    private static final String KEY_BG_URI = "background_uri";
    private final SharedPreferences prefs;
    public BackgroundManager(Context ctx){
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    public void setBackgroundUri(String uri){
        prefs.edit().putString(KEY_BG_URI, uri).apply();
    }
    public void clearBackground(){
        prefs.edit().remove(KEY_BG_URI).apply();
    }
    public String getBackgroundUri(){
        return prefs.getString(KEY_BG_URI, null);
    }
    public String getInjectionHtml(){
        String uri = getBackgroundUri();
        if (uri == null || uri.isEmpty()) return "";
        String esc = uri.replace("\\", "\\\\").replace(""", "\\"");
        StringBuilder sb = new StringBuilder();
        sb.append("<style id=\"injected-bg\">html,body{min-height:100%;background-image:url(\"");
        sb.append(esc);
        sb.append("\");background-size:cover;background-position:center center;background-repeat:no-repeat;background-attachment:fixed;margin:0;padding:0;}</style>");
        sb.append("<script>(function(){function reapply(){if(document.getElementById('injected-bg'))return;var head=document.getElementsByTagName('head')[0];if(head){var div=document.createElement('div');div.innerHTML=\"");
        sb.append("<style id=\\\"injected-bg\\\">html,body{min-height:100%;background-image:url(\\\"");
        sb.append(esc);
        sb.append("\\\");background-size:cover;background-position:center center;background-repeat:no-repeat;background-attachment:fixed;margin:0;padding:0;}</style>");
        sb.append("\\";head.insertBefore(div,head.firstChild);}}document.addEventListener('DOMContentLoaded',reapply);var push=history.pushState;history.pushState=function(){push.apply(history,arguments);setTimeout(reapply,10);};var rep=history.replaceState;history.replaceState=function(){rep.apply(history,arguments);setTimeout(reapply,10);};window.addEventListener('popstate',reapply);reapply();})();</script>");
        return sb.toString();
    }
}
