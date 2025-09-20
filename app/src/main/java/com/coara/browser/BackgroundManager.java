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
        return prefs.getString(KEY_BG_URI, "");
    }
    public String getInjectionHtml(){
    String uri = getBackgroundUri();
    if (uri == null || uri.isEmpty()) return "";

    String esc = uri.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");

    StringBuilder sb = new StringBuilder();

    sb.append("<style id=\"injected-bg-style\">");
    sb.append("html::before, body::before {");
    sb.append(" content: '';");
    sb.append(" position: fixed;");
    sb.append(" left: 0; top: 0; width: 100%; height: 100%;");
    sb.append(" pointer-events: none;");
    sb.append(" z-index: -9999999;");
    sb.append(" background-repeat: no-repeat;");
    sb.append(" background-position: center center;");
    sb.append(" background-size: cover;");
    sb.append(" background-image: url('").append(esc).append("');");
    sb.append("}");
    sb.append("html, body { background-color: transparent !important; }");
    sb.append("</style>");

    sb.append("<script>(function(){");
    sb.append("var uri = '").append(esc).append("';");
    sb.append("function apply(){");
    sb.append(" try{");
    sb.append("  var style = document.getElementById('injected-bg-style');");
    sb.append("  if(style){");
    sb.append("    style.innerHTML = style.innerHTML.replace(/background-image:\\s*url\\([^)]+\\)/i, \"background-image: url('\"+uri+\"')\");");
    sb.append("  }");
    sb.append("  var root = document.getElementById('injected-bg-root');");
    sb.append("  if(!root){");
    sb.append("    root = document.createElement('div');");
    sb.append("    root.id = 'injected-bg-root';");
    sb.append("    root.style.position = 'fixed';");
    sb.append("    root.style.left = '0'; root.style.top = '0';");
    sb.append("    root.style.width = '100%'; root.style.height = '100%';");
    sb.append("    root.style.pointerEvents = 'none';");
    sb.append("    root.style.zIndex = '-9999999';");
    sb.append("    root.style.backgroundRepeat = 'no-repeat';");
    sb.append("    root.style.backgroundPosition = 'center center';");
    sb.append("    root.style.backgroundSize = 'cover';");
    sb.append("    root.style.backgroundImage = \"url('\" + uri + \"')\";");
    sb.append("    try{ document.documentElement.insertBefore(root, document.documentElement.firstChild); } catch(e){ if(document.body) document.body.appendChild(root); }");
    sb.append("  } else {");
    sb.append("    root.style.backgroundImage = \"url('\" + uri + \"')\";");
    sb.append("  }");
    sb.append(" } catch(e){}");
    sb.append("}");
    sb.append("var mo = new MutationObserver(function(){ if(!document.getElementById('injected-bg-root')) apply(); });");
    sb.append("mo.observe(document.documentElement, { childList: true, subtree: true });");
    sb.append("window.addEventListener('popstate', apply);");
    sb.append("window.addEventListener('hashchange', apply);");
    sb.append("document.addEventListener('DOMContentLoaded', apply);");
    sb.append("apply();");
    sb.append("})();</script>");

    return sb.toString();
}

}
