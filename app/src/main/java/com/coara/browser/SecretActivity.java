package com.coara.browser;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebViewDatabase;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebBackForwardList;
import android.webkit.WebResourceRequest;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class SecretActivity extends AppCompatActivity {
    private static volatile boolean DATA_DIR_SUFFIX_SET = false;
    private static final Object DATA_DIR_LOCK = new Object();
    private static String DATA_DIR_SUFFIX = "SecretActivity";


    private static final Pattern CACHE_MODE_PATTERN = Pattern.compile("(^|[/.])(?:(chatx2|yahoo|chatx|chat|auth|nicovideo|login|disk|cgi|session|cloud))($|[/.])", Pattern.CASE_INSENSITIVE);
    private static final String PREF_NAME = "SecretBrowserPrefs";
    private static final String KEY_CURRENT_TAB_ID = "current_tab_id";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_BASIC_AUTH = "basic_auth";
    private static final String KEY_ZOOM_ENABLED = "zoom_enabled";
    private static final String KEY_JS_ENABLED = "js_enabled";
    private static final String KEY_IMG_BLOCK_ENABLED = "img_block_enabled";
    private static final String KEY_UA_ENABLED = "ua_enabled";
    private static final String KEY_DESKUA_ENABLED = "deskua_enabled";
    private static final String KEY_CT3UA_ENABLED = "ct3ua_enabled";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_CURRENT_TAB = "current_tab_index";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String APPEND_STR = " CoaraBrowser";
    private static final String START_PAGE = "file:///android_asset/secret.html";
    private static final int FILE_SELECT_CODE = 1001;
    private static final int MAX_TABS = 6;
    private static final int MAX_HISTORY_SIZE = 100;
    private static final String SENTINEL_FILENAME = "secret_cache_sentinel.txt";
    private static Method sSetSaveFormDataMethod;
    private static Method sSetDatabaseEnabledMethod;
    private static Method sSetAppCacheEnabledMethod;
    private static Method sSetAppCachePathMethod;
    private View findInPageBarView;
    private EditText etFindQuery;
    private TextView tvFindCount;
    private Button btnFindPrev, btnFindNext, btnFindClose;
    private PermissionRequest pendingPermissionRequest = null;
    private ActivityResultLauncher<String[]> permissionRequestLauncher;

    private WebView webView;
    private TextInputEditText urlEditText;
    private ImageView faviconImageView;
    private MaterialButton btnGo;
    private MaterialButton btnNewTab;
    private MaterialToolbar toolbar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FrameLayout webViewContainer;
    private TextView tabCountTextView;
    private ImageView copyButton;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<String> permissionLauncher;
    private SharedPreferences pref;
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4); 
    private final ArrayList<WebView> webViews = new ArrayList<>();
    private int currentTabIndex = 0;
    private int nextTabId = 0;
    private int currentHistoryIndex = -1;
    private int currentMatchIndex = 0;
    private int totalMatches = 0;
    private boolean isBackNavigation = false;
    private final List<Bookmark> bookmarks = new ArrayList<>();
    private final List<HistoryItem> historyItems = new ArrayList<>();

    private boolean darkModeEnabled = false;
    private boolean basicAuthEnabled = false;
    private boolean zoomEnabled = false;
    private boolean jsEnabled = false;
    private boolean imgBlockEnabled = false;
    private boolean uaEnabled = false;
    private boolean deskuaEnabled = false;
    private boolean ct3uaEnabled = false;

    private final Map<WebView, Bitmap> webViewFavicons = new HashMap<>();
    private LruCache<String, Bitmap> faviconCache;
    private final Map<WebView, String> originalUserAgents = new HashMap<>();
    private boolean defaultLoadsImagesAutomatically;
    private boolean defaultLoadsImagesAutomaticallyInitialized = false;
    private WebView preloadedWebView = null;
    private View customView = null;
    private WebChromeClient.CustomViewCallback customViewCallback = null;
    static {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        try {
            sSetSaveFormDataMethod = WebSettings.class.getMethod("setSaveFormData", boolean.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            sSetDatabaseEnabledMethod = WebSettings.class.getMethod("setDatabaseEnabled", boolean.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            sSetAppCacheEnabledMethod = WebSettings.class.getMethod("setAppCacheEnabled", boolean.class);
            sSetAppCachePathMethod = WebSettings.class.getMethod("setAppCachePath", String.class);
        } catch (Exception e) {
            e.printStackTrace();
           }
        }
     }
    public static class Bookmark {
        private final String title;
        private final String url;
        public Bookmark(String title, String url) {
            this.title = title;
            this.url = url;
        }
        public String getTitle() { return title; }
        public String getUrl() { return url; }
    }

    public static class HistoryItem {
        private final String title;
        private final String url;
        public HistoryItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
        public String getTitle() { return title; }
        public String getUrl() { return url; }
    }
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            synchronized (DATA_DIR_LOCK) {
                if (!DATA_DIR_SUFFIX_SET) {
                    try {
                        WebView.setDataDirectorySuffix(DATA_DIR_SUFFIX);
                        DATA_DIR_SUFFIX_SET = true;
                    } catch (Throwable ignored) {
                        DATA_DIR_SUFFIX_SET = true;
                    }
                }
            }
        }
        super.onCreate(savedInstanceState);
    setContentView(R.layout.secret_main);

    toolbar = findViewById(R.id.topAppBar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
        getSupportActionBar().setTitle("");
    }

        pref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        checkSentinelAndClearTabsIfNecessary();
        ensureCacheSentinelExists();
        darkModeEnabled = pref.getBoolean(KEY_DARK_MODE, false);
        basicAuthEnabled = pref.getBoolean(KEY_BASIC_AUTH, false);
        zoomEnabled = pref.getBoolean(KEY_ZOOM_ENABLED, false);
        jsEnabled = pref.getBoolean(KEY_JS_ENABLED, false);
        imgBlockEnabled = pref.getBoolean(KEY_IMG_BLOCK_ENABLED, false);
        uaEnabled = pref.getBoolean(KEY_UA_ENABLED, false);
        deskuaEnabled = pref.getBoolean(KEY_DESKUA_ENABLED, false);
        ct3uaEnabled = pref.getBoolean(KEY_CT3UA_ENABLED, false);

        final int maxMemory = (int)(Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 16; 
             faviconCache = new LruCache<String, Bitmap>(cacheSize) {
         @Override
         protected int sizeOf(String key, Bitmap bitmap) {
         return bitmap.getByteCount() / 1024;
        }
         @Override
        protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
           if (evicted && oldValue != null && !oldValue.isRecycled()) {
            oldValue.recycle(); 
        }
    }
};
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
        loadBookmarks();
        if (!historyItems.isEmpty()) {
            currentHistoryIndex = historyItems.size() - 1;
        }
        initializePersistentFavicons();

        urlEditText = findViewById(R.id.urlEditText);
        urlEditText.setImeOptions(EditorInfo.IME_ACTION_GO);
        faviconImageView = findViewById(R.id.favicon);
        btnGo = findViewById(R.id.btnGo);
        btnNewTab = findViewById(R.id.btnNewTab);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        webViewContainer = findViewById(R.id.webViewContainer);
        tabCountTextView = findViewById(R.id.tabCountTextView);
        tabCountTextView.setOnClickListener(v -> showTabsDialog());

        try {
            WebView initialWebView = createNewWebView();
            initialWebView.setTag(nextTabId);

            nextTabId++;
            webViews.add(initialWebView);
            currentTabIndex = 0;
            webViewContainer.addView(initialWebView);
            initialWebView.loadUrl(START_PAGE);
            } catch (Throwable t) {
                try { clearAllSecretData(); } catch (Exception ignored) {}
                finish();
                return;
            }
        updateTabCount();
        boolean shouldClear = getIntent()
            .getBooleanExtra(MainActivity.EXTRA_CLEAR_HISTORY, false);
        if (shouldClear) {
            clear0();
       }

        preInitializeWebView();
        if (!defaultLoadsImagesAutomaticallyInitialized && !webViews.isEmpty()) {
            defaultLoadsImagesAutomatically = webViews.get(0).getSettings().getLoadsImagesAutomatically();
            defaultLoadsImagesAutomaticallyInitialized = true;
        }
        btnGo.setVisibility(View.GONE);

        copyButton = new ImageView(this);
        copyButton.setImageResource(R.drawable.ic_copy);
        copyButton.setAlpha(0.5f);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("URL", url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(SecretActivity.this, "URLをコピーしました", Toast.LENGTH_SHORT).show();
        });
        ViewGroup parentView = (ViewGroup) urlEditText.getParent();
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        copyButton.setLayoutParams(layoutParams);
        parentView.addView(copyButton);

        urlEditText.setOnFocusChangeListener((v, hasFocus) -> {
            faviconImageView.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            btnNewTab.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            copyButton.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
        });

        swipeRefreshLayout.setOnChildScrollUpCallback((parent1, child) -> {
            WebView current = getCurrentWebView();
            return (current != null && current.getScrollY() > 0);
        });
        swipeRefreshLayout.setOnRefreshListener(() -> {
            WebView current = getCurrentWebView();
            if (current != null) current.reload();
        });
            
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) {
                        Toast.makeText(this, "ストレージ権限が必要です。アプリを終了します", Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        permissionRequestLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(),
        result -> {
            if (pendingPermissionRequest == null) return;
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) {
                    allGranted = false;
                    break;
                }
            }
            try {
                if (allGranted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            pendingPermissionRequest = null;
        }
    );
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri dataUri = result.getData().getData();
                        if (filePathCallback != null) {
                            filePathCallback.onReceiveValue(dataUri != null ? new Uri[]{dataUri} : null);
                        }
                    } else if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(null);
                    }
                    filePathCallback = null;
                });

        urlEditText.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
               (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                keyEvent.getAction() == KeyEvent.ACTION_DOWN)) {
                loadUrl();
                return true;
            }
            return false;
        });
        btnNewTab.setOnClickListener(v -> createNewTab());

        handleIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
        if (customView != null) {
            exitFullScreen();
            return;
        }
        WebView current = getCurrentWebView();
        if (current != null && current.canGoBack()) {  
            current.goBack();
            isBackNavigation = true;
            if (currentHistoryIndex > 0) {
                currentHistoryIndex--; 
            }
        } else if (currentHistoryIndex > 0) {
            isBackNavigation = true;
            currentHistoryIndex--;
            HistoryItem previousItem = historyItems.get(currentHistoryIndex);
            current.loadUrl(previousItem.getUrl());
        } else {
            Toast.makeText(SecretActivity.this, "履歴がありません", Toast.LENGTH_SHORT).show();
           }
         }
       });
    }

    
    private void clearAllSecretData() {
        try {
            for (WebView w : new ArrayList<>(webViews)) {
                try {
                    w.clearHistory();
                    w.clearCache(true);
                    w.clearFormData();
                    w.loadUrl("about:blank");
                    w.stopLoading();
                    w.removeAllViews();
                    w.destroy();
                } catch (Exception ignored) {}
            }
            webViews.clear();
            webViewContainer.removeAllViews();
            currentTabIndex = 0;
            nextTabId = 0;
            historyItems.clear();
            WebStorage.getInstance().deleteAllData();
            WebViewDatabase db = WebViewDatabase.getInstance(SecretActivity.this);
            try { db.clearFormData(); } catch (Exception ignored) {}
            try { db.clearHttpAuthUsernamePassword(); } catch (Exception ignored) {}
            CookieManager cookieManager = CookieManager.getInstance();
            try { cookieManager.removeAllCookies(null); } catch (Exception ignored) {}
            try { cookieManager.flush(); } catch (Exception ignored) {}
            pref.edit().remove(KEY_TABS).remove(KEY_HISTORY).apply();
            File dir = getFilesDir();
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (n.startsWith("tab_state_") || n.startsWith("tab_snapshot_")) {
                            try { f.delete(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void clear0() {
        WebView webView = getCurrentWebView();
    if (webView != null) {
        webView.clearHistory();
        webView.clearCache(true);
        webView.clearFormData();
        webView.clearSslPreferences();
        webView.clearMatches();
        clearTabs();
      }
    WebStorage.getInstance().deleteAllData();
    CookieManager cookieManager = CookieManager.getInstance();
    cookieManager.removeAllCookies(null);
    cookieManager.flush();
     }
    private void saveBundleToFile(Bundle bundle, String fileName) {
        File file = new File(getFilesDir(), fileName);
        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            parcel.recycle();
        }
    }

    private Bundle loadBundleFromFile(String fileName) {
        File file = new File(getFilesDir(), fileName);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            Bundle bundle = Bundle.CREATOR.createFromParcel(parcel);
            parcel.recycle();
            return bundle;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        boolean fromMain = intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_HISTORY, false) || intent.getBooleanExtra("from_main", false);
        if (fromMain) {
            boolean shouldClear = intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_HISTORY, false);
            if (shouldClear) {
                clearAllSecretData();
            }
            setIntent(new Intent());
        } else {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try { clearAllSecretData(); } catch (Exception ignored) {}
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabsState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
    }

    private void saveTabsState() {
        
    }

    private void loadTabsState() {
        String tabsJsonStr = pref.getString(KEY_TABS, "[]");
        int currentTabId = pref.getInt(KEY_CURRENT_TAB_ID, -1);
        try {
            JSONArray tabsArray = new JSONArray(tabsJsonStr);
            webViews.clear();
            webViewContainer.removeAllViews();
            int maxId = 0;
            for (int i = 0; i < tabsArray.length(); i++) {
                JSONObject tabObj = tabsArray.getJSONObject(i);
                int id = tabObj.getInt("id");
                String url = tabObj.optString("url", START_PAGE);
                WebView webView = createNewWebView();
                webView.setTag(id);
                webViews.add(webView);
                if (id > maxId) maxId = id;
            }
            nextTabId = maxId + 1;
            if (webViews.isEmpty()) {
                WebView initialWebView = createNewWebView();
                initialWebView.setTag(nextTabId++);
                webViews.add(initialWebView);
                currentTabIndex = 0;
                webViewContainer.addView(initialWebView);
                initialWebView.loadUrl(START_PAGE);
            } else {
                boolean found = false;
                for (int i = 0; i < webViews.size(); i++) {
                    if ((int) webViews.get(i).getTag() == currentTabId) {
                        currentTabIndex = i;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    currentTabIndex = 0;
                }
                webViewContainer.addView(getCurrentWebView());
            }
        } catch (JSONException e) {
            e.printStackTrace();
            WebView initialWebView = createNewWebView();
            initialWebView.setTag(nextTabId++);
            webViews.clear();
            webViews.add(initialWebView);
            currentTabIndex = 0;
            webViewContainer.removeAllViews();
            webViewContainer.addView(initialWebView);
            initialWebView.loadUrl(START_PAGE);
        } catch (Throwable t) {
            try { clearAllSecretData(); } catch (Exception ignored) {}
            finish();
            return;
        updateTabCount();
    }

    private void updateTabCount() {
        if (tabCountTextView != null) {
            tabCountTextView.setText(String.valueOf(webViews.size()));
        }
    }
    private void checkSentinelAndClearTabsIfNecessary() {
    File cacheDir = getCacheDir();
    File sentinel = new File(cacheDir, SENTINEL_FILENAME);
    if (!sentinel.exists()) {
        SharedPreferences.Editor editor = pref.edit();
        editor.remove(KEY_TABS);
        editor.remove(KEY_CURRENT_TAB);
        editor.apply();
        webViews.clear();
       }
    }
    private void ensureCacheSentinelExists() {
    File cacheDir = getCacheDir();
    File sentinel = new File(cacheDir, SENTINEL_FILENAME);
    if (!sentinel.exists()) {
        try {
            sentinel.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
          }
       }
    }
    private void applyCombinedOptimizations(WebView webView) {
    String js = "javascript:(function() {" +
                "  try {" +
                "    var animatedElements = document.querySelectorAll('.animated, .transition');" +
                "    animatedElements.forEach(function(el) {" +
                "      if (!el.style.transform) {" + 
                "        el.style.transform = 'translateZ(0)';" + 
                "      }" +
                "      if (!el.style.willChange) {" +
                "        el.style.willChange = 'transform, opacity';" +
                "      }" +
                "    });" +
                "    var fixedElements = document.querySelectorAll('.fixed');" +
                "    fixedElements.forEach(function(el) {" +
                "      if (el.style.position !== 'fixed') {" +
                "        el.style.position = 'fixed';" +
                "      }" +
                "    });" +
                "  } catch (e) {" +
                "    console.error('Optimization failed: ' + e.message);" +
                "  }" +
                "})();";
    webView.evaluateJavascript(js, null);
    }
    private void injectLazyLoading(WebView webView) {
    String js = "javascript:(function() {" +
                "  try {" +
                "    var placeholder = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';" +
                "    var images = document.querySelectorAll('img[src^=\"https://i.ytimg.com/\"]:not([data-lazy-loaded])');" +
                "    if (images.length === 0) return;" +
                "    images.forEach(function(img) {" +
                "      img.setAttribute('data-lazy-loaded', 'true');" +
                "      if (img.hasAttribute('src')) {" +
                "        img.setAttribute('data-src', img.src);" +
                "        img.src = placeholder;" +
                "        img.style.opacity = '0';" +
                "        img.style.transition = 'opacity 0.3s';" +
                "        if (!img.style.transform) {" +
                "          img.style.transform = 'translateZ(0)';" +
                "        }" +
                "      }" +
                "    });" +
                "    if ('IntersectionObserver' in window) {" +
                "      var observer = new IntersectionObserver(function(entries) {" +
                "        entries.forEach(function(entry) {" +
                "          if (entry.isIntersecting) {" +
                "            var img = entry.target;" +
                "            if (img.dataset.src) {" +
                "              img.src = img.dataset.src;" +
                "              img.removeAttribute('data-src');" +
                "              img.onload = function() {" +
                "                img.style.opacity = '1';" +
                "              };" +
                "              img.onerror = function() {" +
                "                console.warn('Image load failed: ' + img.src);" +
                "              };" +
                "            }" +
                "            observer.unobserve(img);" +
                "          }" +
                "        });" +
                "      }, { root: null, rootMargin: '0px', threshold: 0.1 });" +
                "      images.forEach(function(img) {" +
                "        observer.observe(img);" +
                "      });" +
                "    } else {" +
                "      var loadImagesOnScroll = function() {" +
                "        images.forEach(function(img) {" +
                "          if (img.dataset.src && isElementInViewport(img)) {" +
                "            img.src = img.dataset.src;" +
                "            img.removeAttribute('data-src');" +
                "            img.onload = function() {" +
                "              img.style.opacity = '1';" +
                "            };" +
                "            img.onerror = function() {" +
                "              console.warn('Image load failed: ' + img.src);" +
                "            };" +
                "          }" +
                "        });" +
                "      };" +
                "      var isElementInViewport = function(el) {" +
                "        var rect = el.getBoundingClientRect();" +
                "        return (" +
                "          rect.top >= 0 &&" +
                "          rect.left >= 0 &&" +
                "          rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&" +
                "          rect.right <= (window.innerWidth || document.documentElement.clientWidth)" +
                "        );" +
                "      };" +
                "      window.addEventListener('scroll', loadImagesOnScroll);" +
                "      window.addEventListener('resize', loadImagesOnScroll);" +
                "      window.addEventListener('load', loadImagesOnScroll);" +
                "      loadImagesOnScroll();" +
                "    }" +
                "  } catch (e) {" +
                "    console.error('Lazy loading failed: ' + e.message);" +
                "  }" +
                "})();";
    webView.evaluateJavascript(js, null);
    }
    private void applyOptimizedSettings(WebSettings settings) {
    settings.setJavaScriptEnabled(true);
    settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
    settings.setAllowFileAccess(true);
    settings.setAllowContentAccess(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setDomStorageEnabled(true);
    settings.setSaveFormData(false);
    settings.setGeolocationEnabled(false);
    settings.setTextZoom(100);
    settings.setDisplayZoomControls(false);
    settings.setBuiltInZoomControls(false);
    settings.setSupportZoom(false);
    settings.setMediaPlaybackRequiresUserGesture(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        WebView.setWebContentsDebuggingEnabled(false);
    }
    CookieManager cookieManager = CookieManager.getInstance();
    cookieManager.setAcceptCookie(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setOffscreenPreRaster(true);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, darkModeEnabled ?
                    WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
    }
    private void preInitializeWebView() {
        runOnUiThread(() -> {
            WebView webView = new WebView(SecretActivity.this);
            WebSettings settings = webView.getSettings();
            applyOptimizedSettings(settings);
            String defaultUA = settings.getUserAgentString();
            settings.setUserAgentString(defaultUA + APPEND_STR);
            preloadedWebView = webView;
        });
    }

    private WebView createNewWebView() {
        WebView webView;
        if (preloadedWebView != null) {
            webView = preloadedWebView;
            preloadedWebView = null;
            preInitializeWebView();
        } else {
            webView = new WebView(this);
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        String defaultUA = settings.getUserAgentString();
        originalUserAgents.put(webView, defaultUA);
        applyOptimizedSettings(settings);
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        if (sSetSaveFormDataMethod != null) {
            try {
                sSetSaveFormDataMethod.invoke(settings, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (sSetDatabaseEnabledMethod != null) {
            try {
                sSetDatabaseEnabledMethod.invoke(settings, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (sSetAppCacheEnabledMethod != null && sSetAppCachePathMethod != null) {
            try {
                sSetAppCacheEnabledMethod.invoke(settings, true);
                sSetAppCachePathMethod.invoke(settings, getCacheDir().getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
               }
            }
         }
        if (zoomEnabled) {
            settings.setBuiltInZoomControls(true);
            settings.setSupportZoom(true);
        } else {
            settings.setBuiltInZoomControls(false);
            settings.setSupportZoom(false);
        }
        settings.setJavaScriptEnabled(!jsEnabled);
        settings.setLoadsImagesAutomatically(!imgBlockEnabled);

        if (uaEnabled) {
            settings.setUserAgentString("DoCoMo/2.0 SH902i(c100;TB)");
        } else if (deskuaEnabled) {
            String desktopUA = defaultUA.replace("Mobile", "").replace("Android", "");
            settings.setUserAgentString(desktopUA + APPEND_STR);
        } else if (ct3uaEnabled) {
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 7.0; TAB-A03-BR3 Build/02.05.000; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Safari/537.36");
        } else {
            settings.setUserAgentString(defaultUA + APPEND_STR);
        }

        webView.addJavascriptInterface(new BlobDownloadInterface(), "BlobDownloader");

        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null) {
                final int type = result.getType();
                final String extra = result.getExtra();
                boolean isDataUrl = extra != null && extra.startsWith("data:");
                if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    final String[] options;
                    if (isDataUrl) {
                        options = new String[] {
                            "リンクをコピー",
                            "リンク先を新しいタブで開く",
                            "画像を保存"
                        };
                    } else {
                        options = new String[] {
                            "リンクをコピー",
                            "リンクをダウンロード",
                            "リンク先を新しいタブで開く",
                            "画像を保存"
                        };
                    }
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                if (isDataUrl) {
                                    if (webViews.size() >= MAX_TABS) {
                                        Toast.makeText(SecretActivity.this,
                                            "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
                                    } else {
                                        WebView newWebView = createNewWebView();
                                        webViews.add(newWebView);
                                        updateTabCount();
                                        switchToTab(webViews.size() - 1);
                                        newWebView.loadUrl(extra);
                                    }
                                } else {
                                    handleDownload(extra, null, null, null, 0);
                                }
                            } else if (which == 2) {
                                if (isDataUrl) {
                                    if (extra != null && !extra.isEmpty()) {
                                        saveImage(extra);
                                    }
                                } else {
                                    if (webViews.size() >= MAX_TABS) {
                                        Toast.makeText(SecretActivity.this,
                                            "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
                                    } else {
                                        WebView newWebView = createNewWebView();
                                        webViews.add(newWebView);
                                        updateTabCount();
                                        switchToTab(webViews.size() - 1);
                                        newWebView.loadUrl(extra);
                                    }
                                }
                            } else if (which == 3 && !isDataUrl) {
                                if (extra != null && !extra.isEmpty()) {
                                    saveImage(extra);
                                }
                            }
                        }).show();
                    return true;
                } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    final String[] options = {
                        "リンクをコピー",
                        "リンクをダウンロード",
                        "リンク先を新しいタブで開く"
                    };
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                handleDownload(extra, null, null, null, 0);
                            } else if (which == 2) {
                                if (webViews.size() >= MAX_TABS) {
                                    Toast.makeText(SecretActivity.this,
                                        "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
                                } else {
                                    WebView newWebView = createNewWebView();
                                    webViews.add(newWebView);
                                    updateTabCount();
                                    switchToTab(webViews.size() - 1);
                                    newWebView.loadUrl(extra);
                                }
                            }
                        }).show();
                    return true;
                } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                    final String[] options;
                    boolean isDataUrlLocal = extra != null && extra.startsWith("data:");
                    if (isDataUrlLocal) {
                        options = new String[] {
                            "リンクをコピー",
                            "画像を保存"
                        };
                    } else {
                        options = new String[] {
                            "リンクをコピー",
                            "リンクをダウンロード",
                            "リンク先を新しいタブで開く",
                            "画像を保存"
                        };
                    }
                    new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                copyLink(extra);
                            } else if (which == 1) {
                                if (isDataUrlLocal) {
                                    if (extra != null && !extra.isEmpty()) {
                                        saveImage(extra);
                                    }
                                } else {
                                    handleDownload(extra, null, null, null, 0);
                                }
                            } else if (which == 2 && !isDataUrlLocal) {
                                if (webViews.size() >= MAX_TABS) {
                                    Toast.makeText(SecretActivity.this,
                                        "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
                                } else {
                                    WebView newWebView = createNewWebView();
                                    webViews.add(newWebView);
                                    updateTabCount();
                                    switchToTab(webViews.size() - 1);
                                    newWebView.loadUrl(extra);
                                }
                            } else if (which == 3 && !isDataUrlLocal) {
                                if (extra != null && !extra.isEmpty()) {
                                    saveImage(extra);
                                }
                            }
                        }).show();
                    return true;
                }
            }
            return false;
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return super.shouldInterceptRequest(view, request);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("tel:")) {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                    return true;
                } else if (url.startsWith("mailto:")) {
                    startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(url)));
                    return true;
                } else if (url.startsWith("intent:")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            if (intent.resolveActivity(getPackageManager()) != null) {
                                startActivity(intent);
                            } else {
                                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                                if (fallbackUrl != null) {
                                    view.loadUrl(fallbackUrl);
                                }
                            }
                            return true;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return false;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("tel:")) {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                    return true;
                } else if (url.startsWith("mailto:")) {
                    startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(url)));
                    return true;
                } else if (url.startsWith("intent:")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            if (intent.resolveActivity(getPackageManager()) != null) {
                                startActivity(intent);
                            } else {
                                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                                if (fallbackUrl != null) {
                                    view.loadUrl(fallbackUrl);
                                }
                            }
                            return true;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return false;
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                String lowerUrl = url.toLowerCase();
                boolean isMatched = CACHE_MODE_PATTERN.matcher(lowerUrl).find();
                if (isMatched) {
                    view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                } else {
                    view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
                }
                urlEditText.setText(url);
                super.onPageStarted(view, url, favicon);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                  super.onPageFinished(view, url);
                  applyCombinedOptimizations(view);
            if (url.startsWith("https://m.youtube.com") || url.startsWith("https://chatgpt.com/")) {  // 特定API対応
             view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);  // SPA内部APIでキャッシュ無効
             new Handler(Looper.getMainLooper()).postDelayed(() -> injectLazyLoading(view), 200);  // 遅延短縮で高速化
            }
            if (url.equals(START_PAGE)) {
             faviconImageView.setVisibility(View.GONE);
             urlEditText.setText("");
            } else {
            faviconImageView.setVisibility(View.VISIBLE);
            if (view == getCurrentWebView()) {
            urlEditText.setText(url);
            }
           }
         if (!isBackNavigation) {
            addHistory(url, view.getTitle()); 
            currentHistoryIndex = historyItems.size() - 1;
         } else {
            isBackNavigation = false;
          }
        if (swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(false);
           }
          String jsOverrideHistory = "(function(){" +
          "function notifyUrlChange(){AndroidBridge.onUrlChange(location.href);}" +
          "var pushState=history.pushState;" +
          "history.pushState=function(){pushState.apply(history,arguments);notifyUrlChange();};" +
          "var replaceState=history.replaceState;" +
          "history.replaceState=function(){replaceState.apply(history,arguments);notifyUrlChange();};" +
          "window.addEventListener('popstate',function(){notifyUrlChange();});" +
          "notifyUrlChange();" +
          "})();"; 
           view.evaluateJavascript(jsOverrideHistory, null); 
          }
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                if (!basicAuthEnabled) {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                    return;
                }
                LinearLayout layout = new LinearLayout(SecretActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                int padding = (int)(16 * getResources().getDisplayMetrics().density);
                layout.setPadding(padding, padding, padding, padding);
                final EditText usernameInput = new EditText(SecretActivity.this);
                usernameInput.setHint("ユーザー名");
                usernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
                layout.addView(usernameInput);
                final EditText passwordInput = new EditText(SecretActivity.this);
                passwordInput.setHint("パスワード");
                passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                layout.addView(passwordInput);
                new MaterialAlertDialogBuilder(SecretActivity.this)
                    .setTitle("Basic認証情報を入力")
                    .setView(layout)
                    .setPositiveButton("ログイン", (dialog, which) -> {
                        String username = usernameInput.getText().toString().trim();
                        String password = passwordInput.getText().toString().trim();
                        if (!username.isEmpty() && !password.isEmpty()) {
                            handler.proceed(username, password);
                        } else {
                            Toast.makeText(SecretActivity.this, "ユーザー名とパスワードを入力してください", Toast.LENGTH_SHORT).show();
                            handler.cancel();
                        }
                    })
                    .setNegativeButton("キャンセル", (dialog, which) -> handler.cancel())
                    .show();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                               FileChooserParams fileChooserParams) {
                SecretActivity.this.filePathCallback = filePathCallback;
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent());
                } catch (Exception e) {
                    SecretActivity.this.filePathCallback = null;
                    Toast.makeText(SecretActivity.this, "ファイル選択エラー", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
           @Override
           public void onPermissionRequest(final PermissionRequest request) {
           try {
            String[] resources = request.getResources();
            List<String> permissionsNeeded = new ArrayList<>();

            for (String resource : resources) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.CAMERA);
                    }
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
            }
            if (permissionsNeeded.isEmpty()) {
                request.grant(resources);
            } else {
                pendingPermissionRequest = request;
                permissionRequestLauncher.launch(permissionsNeeded.toArray(new String[0]));
            }
        } catch (Exception e) {
            e.printStackTrace();
            request.deny();
        }
    }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (view == getCurrentWebView()) {
                    faviconImageView.setImageBitmap(icon);
                }
                webViewFavicons.put(view, icon);
                String currentUrl = view.getUrl();
                if (currentUrl != null) {
                    faviconCache.put(currentUrl, icon);
                    backgroundExecutor.execute(() -> saveFaviconToFile(currentUrl, icon));
                }
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webViewContainer.setVisibility(View.GONE);
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webViewContainer.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("blob:")) {
                handleBlobDownload(url, userAgent, contentDisposition, mimeType, contentLength);
            } else {
                handleDownload(url, userAgent, contentDisposition, mimeType, contentLength);
            }
            if ("external".equals(getCurrentWebView().getTag())) {
                closeTab(getCurrentWebView());
            }
        });
        return webView;
    }

    private void closeTab(WebView webView) {
        int index = webViews.indexOf(webView);
        if (index != -1) {
            if (webViews.size() > 1) {
                webViews.remove(index);
                if (currentTabIndex > index) {
                    currentTabIndex--;
                } else if (currentTabIndex >= webViews.size()) {
                    currentTabIndex = webViews.size() - 1;
                }
                webViewContainer.removeAllViews();
                webViewContainer.addView(getCurrentWebView());
                updateTabCount();
            } else {
                webView.loadUrl(START_PAGE);
            }
        }
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
           ContextCompat.checkSelfPermission(SecretActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
           != PackageManager.PERMISSION_GRANTED) {
            if (permissionLauncher != null) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        if (mimeType != null) {
            request.setMimeType(mimeType);
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        request.addRequestHeader("cookie", cookies);
        if (userAgent != null) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setDescription("Downloading file...");
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        request.setTitle(fileName);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try {
            long downloadId = dm.enqueue(request);
            String filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .getAbsolutePath() + "/" + fileName;
            DownloadHistoryManager.addDownloadHistory(SecretActivity.this, downloadId, fileName, filePath);
            DownloadHistoryManager.monitorDownloadProgress(SecretActivity.this, downloadId, dm);
            Toast.makeText(SecretActivity.this, "ダウンロードを開始しました", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(SecretActivity.this, "ダウンロードに失敗しました", Toast.LENGTH_SHORT).show();
        }
    }
    private void handleBlobDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        String js = "javascript:(function() {" +
                "fetch('" + url + "').then(function(response) {" +
                "  return response.blob();" +
                "}).then(function(blob) {" +
                "  var reader = new FileReader();" +
                "  reader.onloadend = function() {" +
                "    var base64data = reader.result;" +
                "    var fileName = '" + generateBlobFileName(mimeType) + "';" +
                "    window.BlobDownloader.onBlobDownloaded(base64data, '" + (mimeType != null ? mimeType : "application/octet-stream") + "', fileName);" +
                "  };" +
                "  reader.readAsDataURL(blob);" +
                "}).catch(function(error) {" +
                "  window.BlobDownloader.onBlobDownloadError(error.toString());" +
                "});" +
                "})()";
        getCurrentWebView().evaluateJavascript(js, null);
    }

    private String generateBlobFileName(String mimeType) {
        String ext = "";
        if (mimeType != null) {
            if (mimeType.contains("pdf")) {
                ext = ".pdf";
            } else if (mimeType.contains("image/png")) {
                ext = ".png";
            } else if (mimeType.contains("image/jpeg")) {
                ext = ".jpg";
            } else if (mimeType.contains("text/html")) {
                ext = ".html";
            }
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "blob_download_" + timeStamp + ext;
    }

    private class BlobDownloadInterface {
        @JavascriptInterface
        public void onBlobDownloaded(String base64Data, String mimeType, String fileName) {
            runOnUiThread(() -> {
                try {
                    int commaIndex = base64Data.indexOf(",");
                    String pureBase64 = base64Data.substring(commaIndex + 1);
                    byte[] data = Base64.decode(pureBase64, Base64.DEFAULT);
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    File file = new File(downloadDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(data);
                        fos.flush();
                    }
                    Toast.makeText(SecretActivity.this, "blob ダウンロード完了: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(SecretActivity.this, "blob ダウンロードエラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
        @JavascriptInterface
        public void onBlobDownloadError(String errorMessage) {
            runOnUiThread(() -> Toast.makeText(SecretActivity.this, "blob ダウンロードエラー: " + errorMessage, Toast.LENGTH_LONG).show());
        }
    }

    private void saveImage(String imageUrl) {
    try {
        if (imageUrl != null && imageUrl.startsWith("data:")) {
            int commaIndex = imageUrl.indexOf(',');
            if (commaIndex == -1) {
                Toast.makeText(SecretActivity.this, "無効なデータURL", Toast.LENGTH_SHORT).show();
                return;
            }
            String metadata = imageUrl.substring(5, commaIndex); 
            boolean isBase64 = metadata.contains("base64");
            String mimeType = "image/*";
            if (metadata.contains(";")) {
                mimeType = metadata.split(";")[0];
            }
            byte[] imageData;
            if (isBase64) {
                String base64Data = imageUrl.substring(commaIndex + 1);
                imageData = Base64.decode(base64Data, Base64.DEFAULT);
            } else {
                String dataPart = imageUrl.substring(commaIndex + 1);
                imageData = dataPart.getBytes("UTF-8");
            }
            String fileName = "saved_image_" + System.currentTimeMillis();
            if (mimeType.equalsIgnoreCase("image/png")) {
                fileName += ".png";
            } else if (mimeType.equalsIgnoreCase("image/jpeg")) {
                fileName += ".jpg";
            } else if (mimeType.equalsIgnoreCase("image/bmp")) {
                fileName += ".bmp";
            } else if (mimeType.equalsIgnoreCase("image/gif")) {
                fileName += ".gif";
            } else if (mimeType.equalsIgnoreCase("image/img")) {
                fileName += ".img";
            }
            File picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
            File file = new File(picturesDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(imageData);
                fos.flush();
            }
            Toast.makeText(SecretActivity.this,
                "画像の保存が完了しました\n保存先: " + file.getAbsolutePath(),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(SecretActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(SecretActivity.this,
                "ストレージ権限が必要です", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request =
            new DownloadManager.Request(Uri.parse(imageUrl));
        request.setMimeType("image/*");
        String fileName = URLUtil.guessFileName(imageUrl, null, "image/*");
        request.setTitle(fileName);
        request.setDescription("画像を保存中...");
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_PICTURES, fileName);
        dm.enqueue(request);
        Toast.makeText(SecretActivity.this,
            "画像の保存を開始しました", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Toast.makeText(SecretActivity.this,
            "画像の保存に失敗しました", Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    }
}
    private void exportBookmarksToFile() {
        final String bookmarksJson = pref.getString(KEY_BOOKMARKS, "[]");
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final File file;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            file = new File(downloadDir, "JSON-bookmark" + timeStamp + ".txt");
        } else {
            file = new File(downloadDir, timeStamp + "-bookmark.json");
        }
        backgroundExecutor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bookmarksJson.getBytes("UTF-8"));
                fos.flush();
                runOnUiThread(() ->
                    Toast.makeText(SecretActivity.this, "ブックマークをエクスポートしました: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(SecretActivity.this, "ブックマークのエクスポートに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
                e.printStackTrace();
            }
        });
    }
    private void createNewTab() {
        if (webViews.size() >= MAX_TABS) {
            WebView removed = webViews.remove(0);
            removed.destroy();
            if (currentTabIndex > 0) {
                currentTabIndex--;
            }
        }
        WebView newWebView = createNewWebView();
        newWebView.setTag(nextTabId);
        nextTabId++;
        webViews.add(newWebView);
        updateTabCount();
        switchToTab(webViews.size() - 1);
        getCurrentWebView().loadUrl(START_PAGE);
    }
    private void createNewTab(String url) {
        if (webViews.size() >= MAX_TABS) {
            Toast.makeText(this, "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
            return;
        }
        WebView newWebView = createNewWebView();
        webViews.add(newWebView);
        updateTabCount();
        switchToTab(webViews.size() - 1);
        newWebView.loadUrl(url);
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= webViews.size()) return;
        webViewContainer.removeAllViews();
        currentTabIndex = index;
        webViewContainer.addView(getCurrentWebView());
        urlEditText.setText(getCurrentWebView().getUrl());
    }

    private WebView getCurrentWebView() {
        return webViews.get(currentTabIndex);
    }
    private void loadUrl() {
    String input = urlEditText.getText().toString().trim();
    if (input.isEmpty()) return;
    String url;
    if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("intent:")) {
        url = input;
    } else if (input.contains(" ") || !input.contains(".")) {
        try {
            String query = URLEncoder.encode(input, "UTF-8");
            url = "https://www.google.com/search?q=" + query;
        } catch (UnsupportedEncodingException e) {
            Toast.makeText(this, "エンコードエラー", Toast.LENGTH_SHORT).show();
            return;
        }
    } else {
        url = "http://" + input;
    }
        WebView current = getCurrentWebView();
    if (current != null) {
        current.loadUrl(url);
      }
    }
private class AndroidBridge {
    @JavascriptInterface
    public void onUrlChange(final String url) {
        new Handler(Looper.getMainLooper()).post(() -> { 
            addHistory(url, getCurrentWebView().getTitle()); 
            if (url.startsWith("https://m.youtube.com/watch") ||
                url.startsWith("https://chatgpt.com/") ||
                url.startsWith("https://365sns.f5.si/") ||
                url.startsWith("https://m.youtube.com/shorts/")) {
                swipeRefreshLayout.setEnabled(false);
            } else {
                swipeRefreshLayout.setEnabled(true);
            }
            urlEditText.setText(url); 
        });
    }
}
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.top_app_bar_menu2, menu);
        return true;
    }
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem darkModeItem = menu.findItem(R.id.action_dark_mode);
        darkModeItem.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        darkModeItem.setCheckable(true);
        darkModeItem.setChecked(darkModeEnabled);
        MenuItem uaItem = menu.findItem(R.id.action_ua);
        if (uaItem != null) uaItem.setChecked(uaEnabled);
        MenuItem deskuaItem = menu.findItem(R.id.action_deskua);
        if (deskuaItem != null) deskuaItem.setChecked(deskuaEnabled);
        MenuItem ct3uaItem = menu.findItem(R.id.action_ct3ua);
        if (ct3uaItem != null) ct3uaItem.setChecked(ct3uaEnabled);
        MenuItem zoomItem = menu.findItem(R.id.action_zoom_toggle);
        if (zoomItem != null) zoomItem.setChecked(zoomEnabled);
        MenuItem jsItem = menu.findItem(R.id.action_js);
        if (jsItem != null) jsItem.setChecked(jsEnabled);
        MenuItem imgItem = menu.findItem(R.id.action_img);
        if (imgItem != null) imgItem.setChecked(imgBlockEnabled);
        MenuItem basicAuthItem = menu.findItem(R.id.action_basic_auth);
        if (basicAuthItem != null) basicAuthItem.setChecked(basicAuthEnabled);
        return super.onPrepareOptionsMenu(menu);
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_tabs) {
            showTabsDialog();
        } else if (id == R.id.action_dark_mode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                darkModeEnabled = !darkModeEnabled;
                item.setChecked(darkModeEnabled);
                updateDarkMode();
                pref.edit().putBoolean(KEY_DARK_MODE, darkModeEnabled).apply();
                Toast.makeText(this, "ダークモード " + (darkModeEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "この機能はAndroid 10以上で利用可能です", Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.action_Settings) {
            startActivity(new Intent(SecretActivity.this, SettingsActivity.class));
        } else if (id == R.id.action_Secret) {
            clearSecretDataAndReturnToMain();
            return true;
        } else if (id == R.id.action_exec) {
            startActivity(new Intent(SecretActivity.this, exec.class));
        } else if (id == R.id.action_downloads) {
            startActivity(new Intent(SecretActivity.this, DownloadHistoryActivity.class));
        } else if (id == R.id.action_qr) {
            startActivity(new Intent(SecretActivity.this, QrCodeActivity.class));
        } else if (id == R.id.action_pgdl) {
            startActivity(new Intent(SecretActivity.this, pagedl.class));
        } else if (id == R.id.action_txtphoto) {
            startActivity(new Intent(SecretActivity.this, txtphoto.class));
        } else if (id == R.id.action_num) {
            startActivity(new Intent(SecretActivity.this, num.class));
        } else if (id == R.id.action_asciiart) {
            startActivity(new Intent(SecretActivity.this, asciiart.class));
        } else if (id == R.id.action_grep) {
            startActivity(new Intent(SecretActivity.this, grepmd5appActivity.class));
        } else if (id == R.id.action_htmlview) {
            startActivity(new Intent(SecretActivity.this, htmlview.class));
        } else if (id == R.id.action_bookmark_management) {
            showBookmarksManagementDialog();
        } else if (id == R.id.action_find_in_page) {
            showFindInPageBar();
            return true;
        } else if (id == R.id.action_add_bookmark) {
            addBookmark();
        } else if (id == R.id.action_history) {
            showHistoryDialog();
        } else if (id == R.id.action_export) {
            exportBookmarksToFile();
        } else if (id == R.id.action_import) {
            importBookmarksFromFile();
        } else if (id == R.id.action_Dhistory) {
            Intent intent = new Intent(SecretActivity.this, DownloadHistoryActivity.class);
            intent.putExtra("clear_history", true);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_zoom_toggle) {
            if (item.isChecked()) {
                disableZoom();
                zoomEnabled = false;
            } else {
                enableZoom();
                zoomEnabled = true;
            }
            item.setChecked(zoomEnabled);
            pref.edit().putBoolean(KEY_ZOOM_ENABLED, zoomEnabled).apply();
        } else if (id == R.id.action_js) {
            if (item.isChecked()) {
                disablejs();
                jsEnabled = false;
            } else {
                enablejs();
                jsEnabled = true;
            }
            item.setChecked(jsEnabled);
            pref.edit().putBoolean(KEY_JS_ENABLED, jsEnabled).apply();
        } else if (id == R.id.action_img) {
            if (item.isChecked()) {
                disableimgunlock();
                imgBlockEnabled = false;
            } else {
                enableimgblock();
                imgBlockEnabled = true;
            }
            item.setChecked(imgBlockEnabled);
            pref.edit().putBoolean(KEY_IMG_BLOCK_ENABLED, imgBlockEnabled).apply();
        } else if (id == R.id.action_ua) {
            if (!uaEnabled) {
                if (deskuaEnabled) {
                    disabledeskUA();
                    deskuaEnabled = false;
                    pref.edit().putBoolean(KEY_DESKUA_ENABLED, false).apply();
                }
                if (ct3uaEnabled) {
                    disableCT3UA();
                    ct3uaEnabled = false;
                    pref.edit().putBoolean(KEY_CT3UA_ENABLED, false).apply();
                }
                enableUA();
                uaEnabled = true;
            } else {
                disableUA();
                uaEnabled = false;
            }
            item.setChecked(uaEnabled);
            pref.edit().putBoolean(KEY_UA_ENABLED, uaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_deskua) {
            if (!deskuaEnabled) {
                if (uaEnabled) {
                    disableUA();
                    uaEnabled = false;
                    pref.edit().putBoolean(KEY_UA_ENABLED, false).apply();
                }
                if (ct3uaEnabled) {
                    disableCT3UA();
                    ct3uaEnabled = false;
                    pref.edit().putBoolean(KEY_CT3UA_ENABLED, false).apply();
                }
                enabledeskUA();
                deskuaEnabled = true;
            } else {
                disabledeskUA();
                deskuaEnabled = false;
            }
            item.setChecked(deskuaEnabled);
            pref.edit().putBoolean(KEY_DESKUA_ENABLED, deskuaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_ct3ua) {
            if (!ct3uaEnabled) {
                if (uaEnabled) {
                    disableUA();
                    uaEnabled = false;
                    pref.edit().putBoolean(KEY_UA_ENABLED, false).apply();
                }
                if (deskuaEnabled) {
                    disabledeskUA();
                    deskuaEnabled = false;
                    pref.edit().putBoolean(KEY_DESKUA_ENABLED, false).apply();
                }
                enableCT3UA();
                ct3uaEnabled = true;
            } else {
                disableCT3UA();
                ct3uaEnabled = false;
            }
            item.setChecked(ct3uaEnabled);
            pref.edit().putBoolean(KEY_CT3UA_ENABLED, ct3uaEnabled).apply();
            invalidateOptionsMenu();
        } else if (id == R.id.action_basic_auth) {
            if (!basicAuthEnabled) {
                basicAuthEnabled = true;
                item.setChecked(true);
                Toast.makeText(SecretActivity.this, "Basic認証 ON", Toast.LENGTH_SHORT).show();
            } else {
                basicAuthEnabled = false;
                item.setChecked(false);
                clearBasicAuthCacheAndReload();
                Toast.makeText(SecretActivity.this, "Basic認証 OFF", Toast.LENGTH_SHORT).show();
            }
            pref.edit().putBoolean(KEY_BASIC_AUTH, basicAuthEnabled).apply();
        } else if (id == R.id.action_clear_history) {
            WebView current = getCurrentWebView();
            if (current != null) {
                current.clearHistory();
            }
            historyItems.clear();
            saveHistory();
            clearWebStorage();
            clearPageCache();
            clearTabs();
            WebViewDatabase.getInstance(SecretActivity.this).clearFormData();
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
            urlEditText.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            urlEditText.setRawInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            urlEditText.setPrivateImeOptions("nm");
            urlEditText.setAutofillHints("");
            urlEditText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            String currentText = urlEditText.getText().toString();
            urlEditText.setText("");
            urlEditText.setText(currentText);
            Toast.makeText(SecretActivity.this, "履歴、フォームデータ、検索候補、及びタブとCookieを消去しました", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_negapoji) {
            applyNegapoji();
        } else if (id == R.id.action_translate) {
            translatePageToJapanese();
            return true;
        } else if (id == R.id.action_clear_tabs) {
            clearTabs();
        } else if (id == R.id.action_screenshot) {
            takeScreenshot();
        }
        return super.onOptionsItemSelected(item);
    }

    private void clearBasicAuthCacheAndReload() {
        WebView current = getCurrentWebView();
        if (current != null) {
            current.clearCache(true);
            current.reload();
            reloadCurrentPage();
        }
    }

    private void translatePageToJapanese() {
        String currentUrl = getCurrentWebView().getUrl();
        if (currentUrl == null || currentUrl.isEmpty()) {
            Toast.makeText(SecretActivity.this, "翻訳するページが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String encodedUrl = URLEncoder.encode(currentUrl, "UTF-8");
            String translateUrl = "https://translate.google.com/translate?hl=ja&sl=auto&tl=ja&u=" + encodedUrl;
            getCurrentWebView().loadUrl(translateUrl);
        } catch (UnsupportedEncodingException e) {
            Toast.makeText(SecretActivity.this, "翻訳中にエラーが発生しました", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    private void clearSecretDataAndReturnToMain() {
    for (WebView webView : webViews) {
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
    }
    clearPageCache();
    clearTabs();
    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
    WebViewDatabase.getInstance(this).clearFormData();

    CookieManager cookieManager = CookieManager.getInstance();
    cookieManager.removeAllCookies(null);
    cookieManager.flush();

    SharedPreferences secretPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    secretPrefs.edit()
            .remove(KEY_HISTORY)
            .remove(KEY_TABS)
            .apply();

    Intent intent = new Intent(this, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);

    finish();
    }
    private void applyNegapoji() {
        String js = "javascript:(function() {" +
                "document.documentElement.style.filter = 'invert(1)';" +
                "var media = document.getElementsByTagName('img');" +
                "for (var i = 0; i < media.length; i++) {" +
                "    media[i].style.filter = 'invert(1)';" +
                "}" +
                "})()";
        getCurrentWebView().evaluateJavascript(js, null);
    }

    @SuppressWarnings("deprecation")
    private void clearWebStorage() {
        WebStorage.getInstance().deleteAllData();
    }

    private void reloadCurrentPage() {
        WebView currentWebView = getCurrentWebView();
        if (currentWebView != null) {
            currentWebView.clearCache(true);
            String currentUrl = currentWebView.getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                currentWebView.loadUrl(currentUrl);
            }
        }
    }

    private void clearPageCache() {
        for (WebView webView : webViews) {
            webView.clearCache(true);
        }
    }

    private void clearTabs() {
        WebView current = getCurrentWebView();
        current.loadUrl(START_PAGE);
        for (int i = 0; i < webViews.size(); i++) {
            if (i != currentTabIndex) {
                webViews.get(i).destroy();
            }
        }
        webViews.clear();
        webViews.add(current);
        currentTabIndex = 0;
        webViewContainer.removeAllViews();
        webViewContainer.addView(current);
        updateTabCount();
    }

    private void takeScreenshot() {
    View rootView = getWindow().getDecorView().getRootView();
    int width = rootView.getWidth();
    int height = rootView.getHeight();
    if (width <= 0 || height <= 0) {
        Toast.makeText(SecretActivity.this, "スクリーンショット取得エラー: ビューサイズが無効", Toast.LENGTH_SHORT).show();
        return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Handler handler = new Handler(Looper.getMainLooper());
        PixelCopy.request(getWindow(), bitmap, copyResult -> {
            if (copyResult == PixelCopy.SUCCESS) {
                saveScreenshot(bitmap);
            } else {
                Toast.makeText(SecretActivity.this, "スクリーンショットの取得に失敗しました", Toast.LENGTH_SHORT).show();
            }
        }, handler);
    } else {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        rootView.draw(canvas);
        saveScreenshot(bitmap);
    }
}

private void saveScreenshot(Bitmap bitmap) {
    backgroundExecutor.execute(() -> {
        try {
            File screenshotDir = new File(Environment.getExternalStorageDirectory(), "DCIM/Screenshot");
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = timeStamp + ".png";
            File screenshotFile = new File(screenshotDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(screenshotFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            runOnUiThread(() ->
                Toast.makeText(SecretActivity.this, "スクリーンショットを保存しました: " + screenshotFile.getAbsolutePath(), Toast.LENGTH_LONG).show()
            );
        } catch (Exception e) {
            runOnUiThread(() ->
                Toast.makeText(SecretActivity.this, "スクリーンショット保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    });
}

    private void updateDarkMode() {
        for (WebView webView : webViews) {
            WebSettings settings = webView.getSettings();
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, darkModeEnabled ?
                        WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
            }
            if (webView == getCurrentWebView()) {
                webView.reload();
            }
        }
    }

    private void enableCT3UA() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 7.0; TAB-A03-BR3 Build/02.05.000; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Safari/537.36");
        Toast.makeText(SecretActivity.this, "CT3UA適用", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disableCT3UA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA + APPEND_STR);
        } else {
            settings.setUserAgentString(APPEND_STR.trim());
        }
        Toast.makeText(SecretActivity.this, "CT3UA解除", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void enabledeskUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA == null) {
            originalUA = settings.getUserAgentString();
        }
        String desktopUA = originalUA.replace("Mobile", "").replace("Android", "");
        settings.setUserAgentString(desktopUA + APPEND_STR);
        Toast.makeText(SecretActivity.this, "デスクトップ表示有効", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disabledeskUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA + APPEND_STR);
        } else {
            settings.setUserAgentString(APPEND_STR.trim());
        }
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "デスクトップ表示無効", Toast.LENGTH_SHORT).show();
    }

    private void enableUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setUserAgentString("DoCoMo/2.0 SH902i(c100;TB)");
        Toast.makeText(SecretActivity.this, "ガラケーUA有効", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disableUA() {
        WebSettings settings = getCurrentWebView().getSettings();
        String originalUA = originalUserAgents.get(getCurrentWebView());
        if (originalUA != null) {
            settings.setUserAgentString(originalUA + APPEND_STR);
        } else {
            settings.setUserAgentString(APPEND_STR.trim());
        }
        Toast.makeText(SecretActivity.this, "ガラケーUA解除", Toast.LENGTH_SHORT).show();
        reloadCurrentPage();
    }

    private void disablejs() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setJavaScriptEnabled(true);
        Toast.makeText(SecretActivity.this, "JavaScript有効", Toast.LENGTH_SHORT).show();
    }

    private void enablejs() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setJavaScriptEnabled(false);
        Toast.makeText(SecretActivity.this, "JavaScript無効", Toast.LENGTH_SHORT).show();
    }

    private void enableZoom() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(true);
        Toast.makeText(SecretActivity.this, "ズームを有効にしました", Toast.LENGTH_SHORT).show();
    }

    private void disableZoom() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "ズームを無効にしました", Toast.LENGTH_SHORT).show();
    }

    private void enableimgblock() {
        WebSettings settings = getCurrentWebView().getSettings();
        settings.setLoadsImagesAutomatically(false);
        reloadCurrentPage();
        Toast.makeText(SecretActivity.this, "画像ブロック有効", Toast.LENGTH_SHORT).show();
    }

    private void disableimgunlock() {
        WebView webView = getCurrentWebView();
        webView.getSettings().setLoadsImagesAutomatically(defaultLoadsImagesAutomatically);
        webView.clearCache(true);
        webView.reload();
        Toast.makeText(SecretActivity.this, "画像ブロック無効", Toast.LENGTH_SHORT).show();
    }
    private void showFindInPageBar() {
    if (findInPageBarView == null) {
        LayoutInflater inflater = LayoutInflater.from(this);
        findInPageBarView = inflater.inflate(R.layout.find_in_page_bar, null);
        etFindQuery = findInPageBarView.findViewById(R.id.etFindQuery);
        tvFindCount = findInPageBarView.findViewById(R.id.tvFindCount);
        btnFindPrev = findInPageBarView.findViewById(R.id.btnFindPrev);
        btnFindNext = findInPageBarView.findViewById(R.id.btnFindNext);
        btnFindClose = findInPageBarView.findViewById(R.id.btnFindClose);

        etFindQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etFindQuery.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    performFindInPage();
                    return true;
                }
                return false;
            }
        });
        etFindQuery.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    performFindInPage();
                    return true;
                }
                return false;
            }
        });

        btnFindNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (totalMatches > 0) {
                    getCurrentWebView().findNext(true);
                }
            }
        });
        btnFindPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (totalMatches > 0) {
                    getCurrentWebView().findNext(false);
                }
            }
        });
        btnFindClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etFindQuery.setText("");
                hideFindInPageBar();
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        addContentView(findInPageBarView, params);
    }
    findInPageBarView.setVisibility(View.VISIBLE);
    etFindQuery.requestFocus();
    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
        imm.showSoftInput(etFindQuery, InputMethodManager.SHOW_IMPLICIT);
    }
}
private void performFindInPage() {
    String query = etFindQuery.getText().toString().trim();
    if (query.isEmpty()) {
        getCurrentWebView().clearMatches();
        tvFindCount.setText("0/0");
        totalMatches = 0;
        return;
    }
    
    getCurrentWebView().clearMatches();
    currentMatchIndex = 0;
    getCurrentWebView().findAllAsync(query);
    getCurrentWebView().setFindListener(new WebView.FindListener() {
        @Override
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches, boolean isDoneCounting) {
            currentMatchIndex = activeMatchOrdinal;
            totalMatches = numberOfMatches;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (totalMatches > 0) {
                
                        tvFindCount.setText((activeMatchOrdinal + 1) + "/" + totalMatches);
                    } else {
                        tvFindCount.setText("0/0");
                    }
                }
            });
        }
    });
}
private void hideFindInPageBar() {
    if (findInPageBarView != null) {
        findInPageBarView.setVisibility(View.GONE);
        getCurrentWebView().clearMatches();
        if (tvFindCount != null) {
            tvFindCount.setText("0/0");
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etFindQuery.getWindowToken(), 0);
        }
    }
}
private void showHistoryDialog() {
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("履歴")
            .setPositiveButton("閉じる", null)
            .setView(recyclerView)
            .create();
        HistoryAdapter adapter = new HistoryAdapter(historyItems, dialog);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }

    private void showTabsDialog() {
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("タブ")
            .setNegativeButton("閉じる", null)
            .setView(recyclerView)
            .create();
        TabAdapter adapter = new TabAdapter(webViews, dialog);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }

    private void showBookmarksManagementDialog() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません", Toast.LENGTH_SHORT).show();
            return;
        }
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("ブックマーク")
            .setNegativeButton("閉じる", null)
            .setView(recyclerView)
            .create();
        BookmarkAdapter adapter = new BookmarkAdapter(bookmarks, true, dialog);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }

    private void showEditBookmarkDialog(final int position, final BookmarkAdapter adapter) {
        Bookmark bm = bookmarks.get(position);
        View editView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_bookmark, null);
        EditText etTitle = editView.findViewById(R.id.editTitle);
        EditText etUrl = editView.findViewById(R.id.editUrl);
        etTitle.setText(bm.getTitle());
        etUrl.setText(bm.getUrl());
        new MaterialAlertDialogBuilder(SecretActivity.this)
            .setTitle("ブックマーク")
            .setView(editView)
            .setPositiveButton("保存", (dialog, which) -> {
                String newTitle = etTitle.getText().toString().trim();
                String newUrl = etUrl.getText().toString().trim();
                if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                    newUrl = "http://" + newUrl;
                }
                bookmarks.set(position, new Bookmark(newTitle, newUrl));
                saveBookmarks();
                adapter.notifyDataSetChanged();
                Toast.makeText(SecretActivity.this, "保存しました", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    private void addBookmark() {
        String currentUrl = getCurrentWebView().getUrl();
        String title = getCurrentWebView().getTitle();
        if (currentUrl != null && !currentUrl.isEmpty()) {
            bookmarks.add(new Bookmark(title, currentUrl));
            saveBookmarks();
            Toast.makeText(SecretActivity.this, "ブックマークを追加しました", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String jsonStr = pref.getString(KEY_BOOKMARKS, "[]");
        if (!jsonStr.isEmpty()) {
            parseAndAddBookmarks(jsonStr);
        }
    }
    private final ActivityResultLauncher<Intent> filePickerLauncher =
    registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK &&
                result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    handleFileImport(uri);
                } else {
                    Toast.makeText(SecretActivity.this,
                        "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(SecretActivity.this,
                    "ファイル選択がキャンセルされました", Toast.LENGTH_SHORT).show();
            }
        });
    private void importBookmarksFromFile() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        intent.setType("*/*");
    } else {
        intent.setType("application/json");
    }
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    filePickerLauncher.launch(intent);
    }
    private void handleFileImport(Uri uri) {
    try {
        String json = readTextFromUri(uri);
        parseAndImportBookmarks(json);
        Toast.makeText(SecretActivity.this, "ブックマークをインポートしました", Toast.LENGTH_SHORT).show();
    } catch (IOException e) {
        Toast.makeText(SecretActivity.this, "ファイルの読み取りに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    } catch (JSONException e) {
        Toast.makeText(SecretActivity.this, "JSON解析エラー: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        e.printStackTrace();
      }
    }
    private String readTextFromUri(Uri uri) throws IOException {
    StringBuilder builder = new StringBuilder();
    try (InputStream inputStream = getContentResolver().openInputStream(uri);
         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
          }
       }
       return builder.toString();
      }
    private void parseAndImportBookmarks(String jsonStr) throws JSONException {
        JSONArray array = new JSONArray(jsonStr);
        bookmarks.clear();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            String title = obj.optString("title", "Untitled");
            String url = obj.optString("url", "");
            if (!url.isEmpty()) {
                bookmarks.add(new Bookmark(title, url));
                backgroundExecutor.execute(() -> {
                    Bitmap favicon = fetchFavicon(url);
                    if (favicon != null) {
                        runOnUiThread(() -> faviconCache.put(url, favicon));
                        saveFaviconToFile(url, favicon);
                    }
                });
            }
        }
        saveBookmarks();
    }

    private void parseAndAddBookmarks(String jsonStr) {
        try {
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String title = obj.optString("title", "Untitled");
                String url = obj.optString("url", "");
                if (!url.isEmpty()) {
                    bookmarks.add(new Bookmark(title, url));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveBookmarks() {
        JSONArray array = new JSONArray();
        for (Bookmark bm : bookmarks) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("title", bm.getTitle());
                obj.put("url", bm.getUrl());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            array.put(obj);
        }
        pref.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
    }
    private void loadHistory() {
    historyItems.clear();
}

private void saveHistory() {
}

private String normalizeUrl(String url) {  
    try {
        Uri uri = Uri.parse(url);
        return uri.buildUpon().fragment(null).build().toString();  
    } catch (Exception e) {
        return url;
    }
}

private void addHistory(String url, String title) {
    if (url == null || url.isEmpty() || url.equals("about:blank"))
        return;
    String normalizedUrl = normalizeUrl(url);
    if (!historyItems.isEmpty() && normalizeUrl(historyItems.get(historyItems.size() - 1).getUrl()).equals(normalizedUrl))
        return;
    historyItems.add(new HistoryItem(title, url));
    if (historyItems.size() > MAX_HISTORY_SIZE) {
        historyItems.remove(0);
    }
}
    public void exitFullScreen() {
        if (customView != null) {
            FrameLayout decor = (FrameLayout)getWindow().getDecorView();
            decor.removeView(customView);
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            webViewContainer.setVisibility(View.VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void copyLink(String link) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("リンク", link);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(SecretActivity.this, "リンクをコピーしました", Toast.LENGTH_SHORT).show();
    }

    private Bitmap fetchFavicon(String bookmarkUrl) {
        try {
            URL urlObj = new URL(bookmarkUrl);
            String protocol = urlObj.getProtocol();
            String host = urlObj.getHost();
            String faviconUrl = protocol + "://" + host + "/favicon.ico";
            HttpURLConnection connection = (HttpURLConnection) new URL(faviconUrl).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream is = connection.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getFaviconFilename(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".png";
        } catch (Exception e) {
            return Integer.toString(url.hashCode()) + ".png";
        }
    }
    private void saveFaviconToFile(String url, Bitmap bitmap) {
        File faviconsDir = new File(getFilesDir(), "favicons");
        if (!faviconsDir.exists()) {
            faviconsDir.mkdirs();
        }
        File file = new File(faviconsDir, getFaviconFilename(url));
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 50, fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void loadFaviconFromDisk(String url) {
        File faviconsDir = new File(getFilesDir(), "favicons");
        File file = new File(faviconsDir, getFaviconFilename(url));
        if (file.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                faviconCache.put(url, bitmap);
            }
        }
    }
    private void initializePersistentFavicons() {
        for (Bookmark bm : bookmarks) {
            final String url = bm.getUrl();
            backgroundExecutor.execute(() -> loadFaviconFromDisk(url));
        }
        for (HistoryItem hi : historyItems) {
            final String url = hi.getUrl();
            backgroundExecutor.execute(() -> loadFaviconFromDisk(url));
        }
    }

    private class TabAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_TAB = 0;
        private static final int VIEW_TYPE_ADD = 1;
        private final List<WebView> tabs;
        private final AlertDialog dialog;
        public TabAdapter(List<WebView> tabs, AlertDialog dialog) {
            this.tabs = tabs;
            this.dialog = dialog;
        }
        @Override
        public int getItemCount() {
            return tabs.size() + 1;
        }
        @Override
        public int getItemViewType(int position) {
            return (position == tabs.size()) ? VIEW_TYPE_ADD : VIEW_TYPE_TAB;
        }
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_TAB) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tab, parent, false);
                return new TabViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tab_add, parent, false);
                return new AddTabViewHolder(view);
            }
        }
        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_TAB) {
                TabViewHolder tabHolder = (TabViewHolder) holder;
                WebView webView = tabs.get(position);
                String title = webView.getTitle();
                if (title == null || title.isEmpty()) {
                    title = "タブ " + (position + 1);
                }
                tabHolder.title.setText(title);
                Bitmap icon = webViewFavicons.get(webView);
                if (icon != null) {
                    tabHolder.favicon.setImageBitmap(icon);
                } else {
                    tabHolder.favicon.setImageResource(R.drawable.transparent_vector);
                }
                tabHolder.itemView.setOnClickListener(v -> {
                    switchToTab(position);
                    dialog.dismiss();
                });
                tabHolder.closeButton.setOnClickListener(v -> {
                    if (tabs.size() > 1) {
                        tabs.remove(position);
                        notifyItemRemoved(position);
                        if (currentTabIndex >= tabs.size()) {
                            currentTabIndex = tabs.size() - 1;
                        }
                        webViewContainer.removeAllViews();
                        webViewContainer.addView(getCurrentWebView());
                        updateTabCount();
                    } else {
                        Toast.makeText(SecretActivity.this, "これ以上タブを閉じられません", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                AddTabViewHolder addHolder = (AddTabViewHolder) holder;
                addHolder.addButton.setOnClickListener(v -> {
                    createNewTab();
                    notifyItemInserted(tabs.size() - 1);
                    dialog.dismiss();
                });
            }
        }
        class TabViewHolder extends RecyclerView.ViewHolder {
            ImageView favicon;
            TextView title;
            ImageButton closeButton;
            public TabViewHolder(View itemView) {
                super(itemView);
                favicon = itemView.findViewById(R.id.tabFavicon);
                title = itemView.findViewById(R.id.tabTitle);
                closeButton = itemView.findViewById(R.id.tabCloseButton);
            }
        }
        class AddTabViewHolder extends RecyclerView.ViewHolder {
            ImageView addButton;
            public AddTabViewHolder(View itemView) {
                super(itemView);
                addButton = itemView.findViewById(R.id.tabAddButton);
            }
        }
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {
        private final List<HistoryItem> items;
        private final AlertDialog dialog;
        public HistoryAdapter(List<HistoryItem> items, AlertDialog dialog) {
            this.items = items;
            this.dialog = dialog;
        }
        @Override
        public HistoryViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
            return new HistoryViewHolder(view);
        }
        @Override
        public void onBindViewHolder(HistoryViewHolder holder, int position) {
            HistoryItem item = items.get(position);
            holder.title.setText((item.getTitle() != null && !item.getTitle().isEmpty()) ? item.getTitle() : item.getUrl());
            holder.url.setText(item.getUrl());
            Bitmap icon = faviconCache.get(item.getUrl());
            if (icon != null) {
                holder.favicon.setImageBitmap(icon);
            } else {
                holder.favicon.setImageResource(R.drawable.transparent_vector);
            }
            holder.itemView.setOnClickListener(v -> {
                getCurrentWebView().loadUrl(item.getUrl());
                dialog.dismiss();
            });
            holder.itemView.setOnLongClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) return true;
                HistoryItem currentItem = items.get(currentPosition);
                String[] options = {"URLコピー", "削除"};
                new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("操作を選択")
                        .setItems(options, (dialogInterface, which) -> {
                            if (which == 0) {
                                copyLink(currentItem.getUrl());
                            } else if (which == 1) {
                                items.remove(currentPosition);
                                notifyItemRemoved(currentPosition);
                                saveHistory();
                                Toast.makeText(SecretActivity.this, "削除しました", Toast.LENGTH_SHORT).show();
                            }
                        }).show();
                return true;
            });
        }
        @Override
        public int getItemCount() { return items.size(); }
        class HistoryViewHolder extends RecyclerView.ViewHolder {
            ImageView favicon;
            TextView title;
            TextView url;
            public HistoryViewHolder(View itemView) {
                super(itemView);
                favicon = itemView.findViewById(R.id.historyFavicon);
                title = itemView.findViewById(R.id.historyTitle);
                url = itemView.findViewById(R.id.historyUrl);
            }
        }
    }

    private class BookmarkAdapter extends RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder> {
        private final List<Bookmark> items;
        private final boolean managementMode;
        private final AlertDialog dialog;
        public BookmarkAdapter(List<Bookmark> items, boolean managementMode, AlertDialog dialog) {
            this.items = items;
            this.managementMode = managementMode;
            this.dialog = dialog;
        }
        @Override
        public BookmarkViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bookmark, parent, false);
            return new BookmarkViewHolder(view);
        }
        @Override
        public void onBindViewHolder(BookmarkViewHolder holder, int position) {
            Bookmark bm = items.get(position);
            holder.title.setText(bm.getTitle());
            holder.url.setText(bm.getUrl());
            Bitmap icon = faviconCache.get(bm.getUrl());
            if (icon != null) {
                holder.favicon.setImageBitmap(icon);
            } else {
                holder.favicon.setImageResource(R.drawable.transparent_vector);
            }
            holder.itemView.setOnClickListener(v -> {
                getCurrentWebView().loadUrl(bm.getUrl());
                dialog.dismiss();
            });
            if (managementMode) {
            holder.itemView.setOnLongClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) return true;
                String[] options = {"編集", "削除"};
                new MaterialAlertDialogBuilder(SecretActivity.this)
                        .setTitle("操作を選択")
                        .setItems(options, (dialogInterface, which) -> {
                            if (which == 0) {
                                showEditBookmarkDialog(currentPosition, this);
                            } else if (which == 1) {
                                items.remove(currentPosition);
                                notifyItemRemoved(currentPosition);
                                saveBookmarks();
                                Toast.makeText(SecretActivity.this, "削除しました", Toast.LENGTH_SHORT).show();
                            }
                        }).show();
                return true;
            });
        }
    }
        @Override
        public int getItemCount() { return items.size(); }
        class BookmarkViewHolder extends RecyclerView.ViewHolder {
            ImageView favicon;
            TextView title;
            TextView url;
            public BookmarkViewHolder(View itemView) {
                super(itemView);
                favicon = itemView.findViewById(R.id.bookmarkFavicon);
                title = itemView.findViewById(R.id.bookmarkTitle);
                url = itemView.findViewById(R.id.bookmarkUrl);
            }
        }
    }
}
}
