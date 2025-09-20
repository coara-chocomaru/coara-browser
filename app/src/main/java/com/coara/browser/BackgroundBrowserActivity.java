package com.coara.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class BackgroundBrowserActivity extends AppCompatActivity {

    private WebView webView;
    private SharedPreferences pref;
    private static final String PREF_NAME = "AdvancedBrowserPrefs";
    private static final String KEY_BG_BASE64 = "bg_base64";
    private ActivityResultLauncher<Intent> pickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        webView.setLayoutParams(lp);
        root.addView(webView);
        setContentView(root);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (!request.isForMainFrame()) return super.shouldInterceptRequest(view, request);
                    String urlStr = request.getUrl().toString();
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", ws.getUserAgentString());
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.connect();
                    String contentType = conn.getContentType();
                    String encoding = conn.getContentEncoding();
                    InputStream is = conn.getInputStream();
                    if ("gzip".equalsIgnoreCase(encoding)) {
                        is = new GZIPInputStream(new BufferedInputStream(is));
                    } else {
                        is = new BufferedInputStream(is);
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) != -1) baos.write(buffer, 0, n);
                    is.close();
                    String html = baos.toString("UTF-8");
                    String base64 = pref.getString(KEY_BG_BASE64, null);
                    String injection = "";
                    if (base64 != null && base64.length() > 0) {
                        String dataUri = "data:image/jpeg;base64," + base64;
                        String css = "html,body{background:transparent!important;}#coara_bg_div{position:fixed;top:0;left:0;width:100%;height:100%;z-index:-2147483648;pointer-events:none;background-image:url('"
                                + dataUri + "');background-size:cover;background-repeat:no-repeat;background-position:center center;}";
                        String js = "(function(){if(window.__coara_bg_installed)return;window.__coara_bg_installed=true;var d=document.createElement('div');d.id='coara_bg_div';document.documentElement.insertBefore(d,document.documentElement.firstChild);var s=document.createElement('style');s.id='coara_bg_style';s.innerHTML=" + quoted(css) + ";document.head?document.head.appendChild(s):document.documentElement.appendChild(s);var sweep=function(){var els=document.querySelectorAll('body *:not(script):not(style):not(canvas)');for(var i=0;i<els.length;i++){try{var el=els[i];var cs=window.getComputedStyle(el);if(cs && cs.backgroundColor && cs.backgroundColor!=='rgba(0, 0, 0, 0)' && cs.backgroundColor!=='transparent'){el.style.backgroundColor='transparent';}if(cs && cs.backgroundImage && cs.backgroundImage!=='none'){el.style.backgroundImage='none';}}catch(e){}}};sweep();var mo=new MutationObserver(function(){if(window.__coara_bg_scheduled)return;window.__coara_bg_scheduled=true;setTimeout(function(){sweep();window.__coara_bg_scheduled=false;},120);});mo.observe(document.documentElement,{attributes:true,childList:true,subtree:true});})();";
                        injection = "<style id=\"coara_inject_css\">" + css + "</style><script id=\"coara_inject_js\">" + js + "</script>";
                    }
                    String modified;
                    int headClose = html.indexOf("</head>");
                    if (headClose != -1) {
                        modified = html.substring(0, headClose) + injection + html.substring(headClose);
                    } else {
                        modified = injection + html;
                    }
                    Map<String, String> headers = new HashMap<>();
                    for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                        if (e.getKey() == null) continue;
                        String k = e.getKey();
                        if (k.equalsIgnoreCase("content-security-policy") || k.equalsIgnoreCase("content-security-policy-report-only")) continue;
                        String v = String.join(";", e.getValue());
                        headers.put(k, v);
                    }
                    byte[] outBytes = modified.getBytes("UTF-8");
                    ByteArrayInputStream newIs = new ByteArrayInputStream(outBytes);
                    WebResourceResponse resp = new WebResourceResponse("text/html", "UTF-8", newIs);
                    resp.setResponseHeaders(headers);
                    return resp;
                } catch (IOException ex) {
                    return super.shouldInterceptRequest(view, request);
                } catch (Exception ex) {
                    return super.shouldInterceptRequest(view, request);
                }
            }
        });
        pickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                Uri uri = r.getData().getData();
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    backgroundExecutorSaveAndEncode(uri);
                }
            }
        });
        webView.loadUrl("https://www.example.com");
    }

    private void backgroundExecutorSaveAndEncode(Uri uri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();
                int req = getResources().getDisplayMetrics().widthPixels;
                int inSample = 1;
                while (opts.outWidth / inSample > req) inSample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = inSample;
                is = getContentResolver().openInputStream(uri);
                Bitmap bm = BitmapFactory.decodeStream(is, null, opts);
                if (is != null) try { is.close(); } catch (Exception ignored) {}
                if (bm == null) return;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                bm.recycle();
                byte[] bytes = baos.toByteArray();
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                pref.edit().putString(KEY_BG_BASE64, base64).apply();
                runOnUiThread(() -> {
                    Toast.makeText(BackgroundBrowserActivity.this, "背景を設定しました", Toast.LENGTH_SHORT).show();
                    webView.reload();
                });
            } catch (Exception e) {
            }
        }).start();
    }

    private static String quoted(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('\"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\"') { sb.append('\\'); sb.append(c); }
            else if (c == '\n') { sb.append("\\n"); }
            else if (c == '\r') { sb.append("\\r"); }
            else { sb.append(c); }
        }
        sb.append('\"');
        return sb.toString();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0,1,0,"背景を設定");
        menu.add(0,2,0,"背景をクリア");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pickerLauncher.launch(i);
            return true;
        } else if (item.getItemId() == 2) {
            pref.edit().remove(KEY_BG_BASE64).apply();
            webView.reload();
            Toast.makeText(this, "背景をクリアしました", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
