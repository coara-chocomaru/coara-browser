package com.coara.browser;

import android.Manifest;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService; 
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends AppCompatActivity {

private WebView webView;
private static final int FILE_SELECT_CODE = 1001;
private static final String START_PAGE = "file:///android_asset/index.html";
private static final String KEY_TABS = "tabs";
private static final String KEY_CURRENT_TAB = "current_tab_index";
private static final String APPEND_STR = " CoaraBrowser"; // 常に前にスペース付ける
private int currentHistoryIndex = -1;
private boolean isBackNavigation = false;
private TextInputEditText urlEditText;
private ImageView faviconImageView;
private MaterialButton btnGo;
private MaterialButton btnNewTab;
private MaterialToolbar toolbar;
private SwipeRefreshLayout swipeRefreshLayout;
private FrameLayout webViewContainer;
private ActivityResultLauncher<Intent> fileChooserLauncher;
private ValueCallback<Uri[]> filePathCallback;
private ActivityResultLauncher<String> permissionLauncher;
private SharedPreferences pref;
private static final String PREF_NAME = "AdvancedBrowserPrefs";
private static final String KEY_BOOKMARKS = "bookmarks";
private static final String KEY_HISTORY = "history";
private final List<Bookmark> bookmarks = new ArrayList<>();
private final List<HistoryItem> historyItems = new ArrayList<>();
private boolean darkModeEnabled = false;
private static final String CHANNEL_ID = "download_channel";
private final ArrayList<WebView> webViews = new ArrayList<>();
private int currentTabIndex = 0;
private static final int MAX_TABS = 30;
private final Map<WebView, Bitmap> webViewFavicons = new HashMap<>();
private LruCache<String, Bitmap> faviconCache;
private final Map<WebView, String> originalUserAgents = new HashMap<>();
private View customView = null;
private WebChromeClient.CustomViewCallback customViewCallback = null;
private static final Pattern AD_URL_PATTERN = Pattern.compile(
    "doubleclick\\.net|googlesyndication|adservice|adserver|adnxs|quantserve|scorecardresearch|exelator|rubiconproject|adroll|popads|propellerads|admob",
     Pattern.CASE_INSENSITIVE);
private static final int MAX_HISTORY_SIZE = 100;
private TextView tabCountTextView;
private ImageView copyButton;
private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
private boolean basicAuthEnabled = false;

@Override
protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    createNotificationChannel();

    toolbar = findViewById(R.id.topAppBar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
        getSupportActionBar().setTitle("");
    }

    pref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    final int cacheSize = maxMemory / 8;
    faviconCache = new LruCache<String, Bitmap>(cacheSize) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    loadBookmarks();
    loadHistory();
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
    if (pref.contains(KEY_TABS)) {
        loadTabsState();
        if (webViews.isEmpty()) {
            WebView initialWebView = createNewWebView();
            webViews.add(initialWebView);
            currentTabIndex = 0;
            webViewContainer.addView(initialWebView);
            initialWebView.loadUrl(START_PAGE);
        }
    } else {
        WebView initialWebView = createNewWebView();
        webViews.add(initialWebView);
        currentTabIndex = 0;
        webViewContainer.addView(initialWebView);
        initialWebView.loadUrl(START_PAGE);
    }
    updateTabCount();

    btnGo.setVisibility(View.GONE);

    copyButton = new ImageView(this);
    copyButton.setImageResource(R.drawable.ic_copy);
    copyButton.setAlpha(0.5f);
    copyButton.setVisibility(View.GONE);
    copyButton.setOnClickListener(v -> {
        final String url = urlEditText.getText().toString().trim();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("URL", url);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(MainActivity.this, "URLをコピーしました", Toast.LENGTH_SHORT).show();
    });
    ViewGroup parent = (ViewGroup) urlEditText.getParent();
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
    copyButton.setLayoutParams(params);
    parent.addView(copyButton);

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

    fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    final Uri dataUri = result.getData().getData();
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
                (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && keyEvent.getAction() == KeyEvent.ACTION_DOWN)) {
            loadUrl();
            return true;
        }
        return false;
    });

    btnNewTab.setOnClickListener(v -> createNewTab());

    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (customView != null) {
                exitFullScreen();
                return;
            }
            WebView current = getCurrentWebView();
            if (currentHistoryIndex > 0) {
                isBackNavigation = true;
                currentHistoryIndex--;
                HistoryItem previousItem = historyItems.get(currentHistoryIndex);
                current.loadUrl(previousItem.getUrl());
            } else if (current != null && current.canGoBack()) {
                current.goBack();
            } else {
                Toast.makeText(MainActivity.this, "履歴がありません", Toast.LENGTH_SHORT).show();
            }
        }
    });
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
    JSONArray tabsArray = new JSONArray();
    for (WebView webView : webViews) {
        String url = webView.getUrl();
        tabsArray.put(url != null ? url : "");
    }
    pref.edit()
            .putString(KEY_TABS, tabsArray.toString())
            .putInt(KEY_CURRENT_TAB, currentTabIndex)
            .apply();
}

private void loadTabsState() {
    final String tabsJsonStr = pref.getString(KEY_TABS, "[]");
    final int savedCurrentTab = pref.getInt(KEY_CURRENT_TAB, 0);
    try {
        JSONArray tabsArray = new JSONArray(tabsJsonStr);
        webViews.clear();
        webViewContainer.removeAllViews();
        for (int i = 0; i < tabsArray.length(); i++) {
            String url = tabsArray.getString(i);
            WebView webView = createNewWebView();
            webViews.add(webView);
            webView.loadUrl((url != null && !url.isEmpty()) ? url : START_PAGE);
        }
        if (webViews.isEmpty()) {
            WebView initialWebView = createNewWebView();
            webViews.add(initialWebView);
            currentTabIndex = 0;
            webViewContainer.addView(initialWebView);
            initialWebView.loadUrl(START_PAGE);
        } else {
            currentTabIndex = Math.min(savedCurrentTab, webViews.size() - 1);
            webViewContainer.addView(getCurrentWebView());
        }
    } catch (JSONException e) {
        e.printStackTrace();
        WebView initialWebView = createNewWebView();
        webViews.clear();
        webViews.add(initialWebView);
        currentTabIndex = 0;
        webViewContainer.removeAllViews();
        webViewContainer.addView(initialWebView);
        initialWebView.loadUrl(START_PAGE);
    }
    updateTabCount();
}

    private WebView createNewWebView() {
    WebView webView = new WebView(this);
    webView.setBackgroundColor(Color.WHITE);
    webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

    final WebSettings settings = webView.getSettings();
    final String defaultUA = settings.getUserAgentString();
    originalUserAgents.put(webView, defaultUA);
    settings.setUserAgentString(defaultUA + APPEND_STR);
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(true);
    settings.setAllowContentAccess(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setSafeBrowsingEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setGeolocationEnabled(false);
    settings.setTextZoom(100);
    settings.setBlockNetworkImage(true);
    settings.setDisplayZoomControls(false);
    settings.setBuiltInZoomControls(false);
    settings.setSupportZoom(false);
    settings.setMediaPlaybackRequiresUserGesture(false);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        try {
            Method setAppCacheEnabled = settings.getClass().getMethod("setAppCacheEnabled", boolean.class);
            setAppCacheEnabled.invoke(settings, true);
        } catch (NoSuchMethodException e) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.setOffscreenPreRaster(true);
        settings.setSafeBrowsingEnabled(false);
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(settings,
                darkModeEnabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
    }
    webView.addJavascriptInterface(new BlobDownloadInterface(), "BlobDownloader");

    webView.setOnLongClickListener(v -> {
        WebView.HitTestResult result = webView.getHitTestResult();
        if (result != null) {
            final int type = result.getType();
            final String extra = result.getExtra();
            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                final String[] options = {"リンクをコピー", "リンクをダウンロード", "リンク先を新しいタブで開く", "画像を保存"};
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("リンク", extra);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(MainActivity.this, "リンクをコピーしました", Toast.LENGTH_SHORT).show();
                            } else if (which == 1) {
                                handleDownload(extra, null, null, null, 0);
                            } else if (which == 2) {
                                if (webViews.size() >= MAX_TABS) {
                                    Toast.makeText(MainActivity.this, "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
                                } else {
                                    WebView newWebView = createNewWebView();
                                    webViews.add(newWebView);
                                    updateTabCount();
                                    switchToTab(webViews.size() - 1);
                                    newWebView.loadUrl(extra);
                                }
                            } else if (which == 3) {
                                if (extra != null && !extra.isEmpty()) {
                                    saveImage(extra);
                                }
                            }
                        }).show();
                return true;
            } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                final String[] options = {"リンクをコピー", "リンクをダウンロード", "リンク先を新しいタブで開く"};
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("リンク", extra);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(MainActivity.this, "リンクをコピーしました", Toast.LENGTH_SHORT).show();
                            } else if (which == 1) {
                                handleDownload(extra, null, null, null, 0);
                            } else if (which == 2) {
                                if (webViews.size() >= MAX_TABS) {
                                    Toast.makeText(MainActivity.this, "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
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
                final String[] options = {"画像を保存"};
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("オプションを選択")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0 && extra != null && !extra.isEmpty()) {
                                saveImage(extra);
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
        final String url = request.getUrl().toString();
        if (isAdUrl(url)) {
            return new WebResourceResponse("text/plain", "UTF-8",
                    new ByteArrayInputStream("".getBytes()));
        }
        return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (url.startsWith("intent:")) {
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
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.getSettings().setBlockNetworkImage(false);
            if (view == getCurrentWebView()) {
                urlEditText.setText(url);
            }
            if (!isBackNavigation) {
                if (historyItems.size() > currentHistoryIndex + 1) {
                    historyItems.subList(currentHistoryIndex + 1, historyItems.size()).clear();
                }
                if (historyItems.isEmpty() || !historyItems.get(historyItems.size() - 1).getUrl().equals(url)) {
                    historyItems.add(new HistoryItem(view.getTitle(), url));
                    if (historyItems.size() > MAX_HISTORY_SIZE) {
                        historyItems.remove(0);
                    }
                    currentHistoryIndex = historyItems.size() - 1;
                    saveHistory();
                }
            } else {
                isBackNavigation = false;
            }
            if (swipeRefreshLayout.isRefreshing()) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            if (!basicAuthEnabled) {
                super.onReceivedHttpAuthRequest(view, handler, host, realm);
                return;
            }
            LinearLayout layout = new LinearLayout(MainActivity.this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            final EditText usernameInput = new EditText(MainActivity.this);
            usernameInput.setHint("ユーザー名");
            usernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            layout.addView(usernameInput);

            final EditText passwordInput = new EditText(MainActivity.this);
            passwordInput.setHint("パスワード");
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            layout.addView(passwordInput);

            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("Basic認証情報を入力")
                    .setView(layout)
                    .setPositiveButton("ログイン", (dialog, which) -> {
                        String username = usernameInput.getText().toString().trim();
                        String password = passwordInput.getText().toString().trim();
                        if (!username.isEmpty() && !password.isEmpty()) {
                            handler.proceed(username, password);
                        } else {
                            Toast.makeText(MainActivity.this, "ユーザー名とパスワードを入力してください", Toast.LENGTH_SHORT).show();
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
            MainActivity.this.filePathCallback = filePathCallback;
            try {
                fileChooserLauncher.launch(fileChooserParams.createIntent());
            } catch (Exception e) {
                MainActivity.this.filePathCallback = null;
                Toast.makeText(MainActivity.this, "ファイル選択エラー", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            if (view == getCurrentWebView()) {
                faviconImageView.setImageBitmap(icon);
            }
            webViewFavicons.put(view, icon);
            final String currentUrl = view.getUrl();
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
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
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
    });

    return webView;
}

private void handleDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
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
        DownloadHistoryManager.addDownloadHistory(MainActivity.this, downloadId, fileName, filePath);
        DownloadHistoryManager.monitorDownloadProgress(MainActivity.this, downloadId, dm);
        Toast.makeText(MainActivity.this, "ダウンロードを開始しました", Toast.LENGTH_LONG).show();
    } catch (Exception e) {
        Toast.makeText(MainActivity.this, "ダウンロードに失敗しました", Toast.LENGTH_SHORT).show();
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
    @android.webkit.JavascriptInterface
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
                Toast.makeText(MainActivity.this, "blob ダウンロード完了: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "blob ダウンロードエラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @android.webkit.JavascriptInterface
    public void onBlobDownloadError(String errorMessage) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "blob ダウンロードエラー: " + errorMessage, Toast.LENGTH_LONG).show());
    }
}

private void saveImage(String imageUrl) {
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(MainActivity.this, "ストレージ権限が必要です", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(imageUrl));
        request.setMimeType("image/*");
        String fileName = URLUtil.guessFileName(imageUrl, null, "image/*");
        request.setTitle(fileName);
        request.setDescription("画像を保存中...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, fileName);
        dm.enqueue(request);
        Toast.makeText(MainActivity.this, "画像の保存を開始しました", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Toast.makeText(MainActivity.this, "画像の保存に失敗しました", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(MainActivity.this, "ブックマークをエクスポートしました: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show()
            );
        } catch (Exception e) {
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "ブックマークのエクスポートに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show()
            );
            e.printStackTrace();
        }
    });
}

private void createNewTab() {
    if (webViews.size() >= MAX_TABS) {
        Toast.makeText(this, "最大タブ数に達しました", Toast.LENGTH_SHORT).show();
        return;
    }
    WebView newWebView = createNewWebView();
    webViews.add(newWebView);
    updateTabCount();
    switchToTab(webViews.size() - 1);
    getCurrentWebView().loadUrl(START_PAGE);
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
    String url = urlEditText.getText().toString().trim();
    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("intent:")) {
        url = "http://" + url;
    }
    WebView current = getCurrentWebView();
    if (current != null) {
        current.loadUrl(url);
    }
}

@Override
public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.top_app_bar_menu, menu);
    return true;
}

@Override
public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem darkModeItem = menu.findItem(R.id.action_dark_mode);
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        darkModeItem.setVisible(false);
    } else {
        darkModeItem.setVisible(true);
    }
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
            Toast.makeText(this, "ダークモード " + (darkModeEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "この機能はAndroid 10以上で利用可能です", Toast.LENGTH_SHORT).show();
        }
    } else if (id == R.id.action_downloads) {
        startActivity(new Intent(MainActivity.this, DownloadHistoryActivity.class));
    } else if (id == R.id.action_bookmark_management) {
        showBookmarksManagementDialog();
    } else if (id == R.id.action_add_bookmark) {
        addBookmark();
    } else if (id == R.id.action_history) {
        showHistoryDialog();
    } else if (id == R.id.action_export) {
        exportBookmarksToFile();
    } else if (id == R.id.action_import) {
        importBookmarksFromFile();
    } else if (id == R.id.action_Dhistory) {
        Intent intent = new Intent(this, DownloadHistoryActivity.class);
        intent.putExtra("clear_history", true);
        startActivity(intent);
        return true;
    } else if (id == R.id.action_zoom_toggle) {
        if (item.isChecked()) {
            disableZoom();
        } else {
            enableZoom();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_js) {
        if (item.isChecked()) {
            enablejs();
        } else {
            disablejs();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_img) {
        if (item.isChecked()) {
            disableimgunlock();
        } else {
            enableimgblock();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_ua) {
        if (item.isChecked()) {
            disableUA();
        } else {
            enableUA();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_deskua) {
        if (item.isChecked()) {
            disabledeskUA();
        } else {
            enabledeskUA();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_ct3ua) {
        if (item.isChecked()) {
            disableCT3UA();
        } else {
            enableCT3UA();
        }
        item.setChecked(!item.isChecked());
    } else if (id == R.id.action_basic_auth) {
        if (!basicAuthEnabled) {
            basicAuthEnabled = true;
            item.setChecked(true);
            Toast.makeText(MainActivity.this, "Basic認証 ON", Toast.LENGTH_SHORT).show();
        } else {
            basicAuthEnabled = false;
            item.setChecked(false);
            clearBasicAuthCacheAndReload();
            Toast.makeText(MainActivity.this, "Basic認証 OFF", Toast.LENGTH_SHORT).show();
        }
    } else if (id == R.id.action_clear_history) {
        WebView current = getCurrentWebView();
        if (current != null) {
            current.clearHistory();
        }
        historyItems.clear();
        saveHistory();
        clearWebStorage();
        clearPageCache();
        WebViewDatabase.getInstance(MainActivity.this).clearFormData();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();

        urlEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlEditText.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlEditText.setPrivateImeOptions("nm");
        urlEditText.setAutofillHints("");
        urlEditText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        String currentText = urlEditText.getText().toString();
        urlEditText.setText("");
        urlEditText.setText(currentText);
        Toast.makeText(MainActivity.this, "履歴、フォームデータ、検索候補、及びCookieを消去しました", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(MainActivity.this, "翻訳するページが見つかりません", Toast.LENGTH_SHORT).show();
        return;
    }
    try {
        String encodedUrl = URLEncoder.encode(currentUrl, "UTF-8");
        String translateUrl = "https://translate.google.com/translate?hl=ja&sl=auto&tl=ja&u=" + encodedUrl;
        getCurrentWebView().loadUrl(translateUrl);
    } catch (UnsupportedEncodingException e) {
        Toast.makeText(MainActivity.this, "翻訳中にエラーが発生しました", Toast.LENGTH_SHORT).show();
        e.printStackTrace();
    }
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
    final int width = rootView.getWidth();
    final int height = rootView.getHeight();
    if (width <= 0 || height <= 0) {
        Toast.makeText(MainActivity.this, "スクリーンショット取得エラー: ビューサイズが無効", Toast.LENGTH_SHORT).show();
        return;
    }
    final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    final Handler handler = new Handler(Looper.getMainLooper());
    PixelCopy.request(getWindow(), bitmap, copyResult -> {
        if (copyResult == PixelCopy.SUCCESS) {
            saveScreenshot(bitmap);
        } else {
            Toast.makeText(MainActivity.this, "スクリーンショットの取得に失敗しました", Toast.LENGTH_SHORT).show();
        }
    }, handler);
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
                    Toast.makeText(MainActivity.this, "スクリーンショットを保存しました: " + screenshotFile.getAbsolutePath(), Toast.LENGTH_LONG).show()
            );
        } catch (Exception e) {
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "スクリーンショット保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    });
}

private void updateDarkMode() {
    for (WebView webView : webViews) {
        WebSettings settings = webView.getSettings();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, darkModeEnabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
        if (webView == getCurrentWebView()) {
            webView.reload();
        }
    }
}

private void enableCT3UA() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setUserAgentString("Mozilla/5.0 (Linux; Android 7.0; TAB-A03-BR3 Build/02.05.000; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 Safari/537.36");
    Toast.makeText(MainActivity.this, "CT3UA適用", Toast.LENGTH_SHORT).show();
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
    Toast.makeText(MainActivity.this, "CT3UA解除", Toast.LENGTH_SHORT).show();
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
    Toast.makeText(MainActivity.this, "デスクトップ表示有効", Toast.LENGTH_SHORT).show();
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
    Toast.makeText(MainActivity.this, "デスクトップ表示無効", Toast.LENGTH_SHORT).show();
}

private void enableUA() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setUserAgentString("DoCoMo/2.0 SH902i(c100;TB)");
    Toast.makeText(MainActivity.this, "ガラケーUA有効", Toast.LENGTH_SHORT).show();
    reloadCurrentPage();
}

private void disableUA() {
    WebSettings settings = getCurrentWebView().getSettings();
    String originalUA = originalUserAgents.get(getCurrentWebView());
    if (originalUA != null) {
        settings.setUserAgentString(originalUA + "Coarabrowser");
    } else {
        settings.setUserAgentString("Coarabrowser");
    }
    Toast.makeText(MainActivity.this, "ガラケーUA解除", Toast.LENGTH_SHORT).show();
    reloadCurrentPage();
}

private void clearPageCache() {
    for (WebView webView : webViews) {
        webView.clearCache(true);
    }
}

private void enableimgblock() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setLoadsImagesAutomatically(false);
    reloadCurrentPage();
    Toast.makeText(MainActivity.this, "画像ブロック有効", Toast.LENGTH_SHORT).show();
}

private void disableimgunlock() {
    WebView webView = getCurrentWebView();
    String currentUrl = webView.getUrl();
    WebSettings oldSettings = webView.getSettings();
    WebSettings newSettings = new WebView(this).getSettings();
    oldSettings.setLoadsImagesAutomatically(newSettings.getLoadsImagesAutomatically());
    webView.clearCache(true);
    webView.reload();
    Toast.makeText(MainActivity.this, "画像ブロック無効", Toast.LENGTH_SHORT).show();
}

private void enablejs() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setJavaScriptEnabled(true);
    Toast.makeText(MainActivity.this, "JavaScript有効", Toast.LENGTH_SHORT).show();
}

private void disablejs() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setJavaScriptEnabled(false);
    Toast.makeText(MainActivity.this, "JavaScript無効", Toast.LENGTH_SHORT).show();
}

private void enableZoom() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setBuiltInZoomControls(true);
    settings.setSupportZoom(true);
    Toast.makeText(MainActivity.this, "ズームを有効にしました", Toast.LENGTH_SHORT).show();
}

private void disableZoom() {
    WebSettings settings = getCurrentWebView().getSettings();
    settings.setBuiltInZoomControls(false);
    settings.setSupportZoom(false);
    reloadCurrentPage();
    Toast.makeText(MainActivity.this, "ズームを無効にしました", Toast.LENGTH_SHORT).show();
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
    new MaterialAlertDialogBuilder(this)
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
                Toast.makeText(MainActivity.this, "保存しました", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(MainActivity.this, "ブックマークを追加しました", Toast.LENGTH_SHORT).show();
    }
}

private void loadBookmarks() {
    bookmarks.clear();
    String jsonStr = pref.getString(KEY_BOOKMARKS, "[]");
    if (!jsonStr.isEmpty()) {
        parseAndAddBookmarks(jsonStr);
    }
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
    String jsonStr = pref.getString(KEY_HISTORY, "[]");
    try {
        JSONArray array = new JSONArray(jsonStr);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            String title = obj.getString("title");
            String url = obj.getString("url");
            historyItems.add(new HistoryItem(title, url));
        }
    } catch (JSONException e) {
        e.printStackTrace();
    }
}

private void saveHistory() {
    JSONArray array = new JSONArray();
    for (HistoryItem item : historyItems) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", item.getTitle());
            obj.put("url", item.getUrl());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        array.put(obj);
    }
    pref.edit().putString(KEY_HISTORY, array.toString()).apply();
}

private void addHistory(String url, String title) {
    if (url == null || url.isEmpty() || url.equals("about:blank"))
        return;
    if (!historyItems.isEmpty() && historyItems.get(historyItems.size() - 1).getUrl().equals(url))
        return;
    historyItems.add(new HistoryItem(title, url));
    if (historyItems.size() > MAX_HISTORY_SIZE) {
        historyItems.remove(0);
    }
    saveHistory();
}

private boolean isAdUrl(String url) {
    if (url == null || url.isEmpty())
        return false;
    Matcher matcher = AD_URL_PATTERN.matcher(url);
    return matcher.find();
}

private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        CharSequence name = "Downloads";
        String description = "Download notifications";
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }
}

public void exitFullScreen() {
    if (customView != null) {
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
                    Toast.makeText(MainActivity.this, "これ以上タブを閉じられません", Toast.LENGTH_SHORT).show();
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
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new HistoryViewHolder(view);
    }
    @Override
    public void onBindViewHolder(HistoryViewHolder holder, int position) {
        HistoryItem item = items.get(position);
        holder.title.setText((item.getTitle() != null && !item.getTitle().isEmpty()) ?
                item.getTitle() : item.getUrl());
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
            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("操作を選択")
                    .setItems(options, (dialogInterface, which) -> {
                        if (which == 0) {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("URL", currentItem.getUrl());
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(MainActivity.this, "URLをコピーしました", Toast.LENGTH_SHORT).show();
                        } else if (which == 1) {
                            items.remove(currentPosition);
                            notifyItemRemoved(currentPosition);
                            saveHistory();
                            Toast.makeText(MainActivity.this, "削除しました", Toast.LENGTH_SHORT).show();
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
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("操作を選択")
                        .setItems(options, (dialogInterface, which) -> {
                            if (which == 0) {
                                showEditBookmarkDialog(currentPosition, this);
                            } else if (which == 1) {
                                items.remove(currentPosition);
                                notifyItemRemoved(currentPosition);
                                saveBookmarks();
                                Toast.makeText(MainActivity.this, "削除しました", Toast.LENGTH_SHORT).show();
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

private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    handleFileImport(uri);
                } else {
                    showToast("ファイルが選択されませんでした");
                }
            } else {
                showToast("ファイル選択がキャンセルされました");
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
        showToast("ブックマークをインポートしました");
    } catch (IOException e) {
        showToast("ファイルの読み取りに失敗しました: " + e.getMessage());
        e.printStackTrace();
    } catch (JSONException e) {
        showToast("JSON解析エラー: " + e.getMessage());
        e.printStackTrace();
    }
}
private Bitmap fetchFavicon(String bookmarkUrl) {
    try {
        URL urlObj = new URL(bookmarkUrl);
        String protocol = urlObj.getProtocol();
        String host = urlObj.getHost();
        String faviconUrl = protocol + "://" + host + "/favicon.ico";
        HttpURLConnection connection = (HttpURLConnection) new URL(faviconUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.connect();
        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
            InputStream is = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    return null;
}

private String readTextFromUri(Uri uri) throws IOException {
    StringBuilder builder = new StringBuilder();
    try (InputStream inputStream = getContentResolver().openInputStream(uri);
         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
    }
    return builder.toString();
}

private void showToast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
}

private void updateTabCount() {
    if (tabCountTextView != null) {
        tabCountTextView.setText(String.valueOf(webViews.size()));
    }
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
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
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
}
