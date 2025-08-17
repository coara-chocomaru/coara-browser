package com.coara.browser;

import android.os.Bundle;
import android.os.Environment;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Base64;
import android.webkit.CookieManager;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class pagedl extends AppCompatActivity {
    private static final String TAG = "pagedl";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Mobile; rv:89.0) Gecko/89.0 Firefox/89.0";
    private static final String ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7";

    private EditText urlInput;
    private Switch jsSwitch;
    private Switch localPathSwitch;
    private Button saveButton;
    private WebView webView;
    private volatile boolean isSaving = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pgdl);

        urlInput = findViewById(R.id.urlInput);
        jsSwitch = findViewById(R.id.jsSwitch);
        localPathSwitch = findViewById(R.id.localPathSwitch);
        saveButton = findViewById(R.id.saveButton);
        webView = findViewById(R.id.webView);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(jsSwitch.isChecked());
        jsSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                webSettings.setJavaScriptEnabled(isChecked));

        saveButton.setOnClickListener(v -> handleSaveButtonClick());
    }

    private void handleSaveButtonClick() {
        final String urlString = urlInput.getText().toString().trim();
        if (urlString.isEmpty()) {
            Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        final String siteName = urlString.replaceAll("[^a-zA-Z0-9]", "_");
        isSaving = true;
        if (urlString.startsWith("blob:")) {
            Toast.makeText(this, "blob: URLはサポートされていません", Toast.LENGTH_LONG).show();
            isSaving = false;
            return;
        }
        if (urlString.startsWith("data:")) {
            executor.execute(() -> saveDataUrl(urlString, siteName));
            return;
        }
        if (jsSwitch.isChecked()) {
            saveWithJavaScriptEnabled(urlString, siteName);
        } else {
            executor.execute(() -> saveWithoutJavaScript(urlString, siteName));
        }
    }

    private void saveWithJavaScriptEnabled(String urlString, String siteName) {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String pageTitle = view.getTitle();
                if (pageTitle == null || pageTitle.isEmpty()) {
                    pageTitle = "untitled";
                }
                String safePageTitle = pageTitle.replaceAll("[^a-zA-Z0-9]", "_");
                File outputDir = getOutputDirectory(siteName, safePageTitle);
                if (outputDir == null) {
                    isSaving = false;
                    return;
                }
                handler.postDelayed(() -> {
                    String archivePath = new File(outputDir, "page_archive.mht").getAbsolutePath();
                    webView.saveWebArchive(archivePath, false, value -> {
                        File archiveFile = (value != null) ? new File(value) : null;
                        if (value == null || archiveFile == null || !archiveFile.exists()) {
                            Log.e(TAG, "saveWebArchive 失敗。返り値: " + value);
                            runOnUiThread(() ->
                                    Toast.makeText(pagedl.this, "Web Archive 保存失敗", Toast.LENGTH_LONG).show());
                            isSaving = false;
                            return;
                        }
                        if (localPathSwitch.isChecked()) {
                            executor.execute(() -> rewriteAndSaveArchive(archiveFile, outputDir));
                        } else {
                            runOnUiThread(() -> {
                                Toast.makeText(pagedl.this, "Web Archive 保存完了：\n" + archiveFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                                clearCacheAndCookies();
                            });
                            isSaving = false;
                        }
                    });
                }, 3000);
            }
        });
        webView.loadUrl(urlString);
    }

    private void rewriteAndSaveArchive(File archiveFile, File outputDir) {
        try {
            String originalContent = Utils.readFileToString(archiveFile);
            String rewrittenContent = MimeParser.rewriteContentLocations(originalContent, outputDir);
            File rewrittenFile = new File(outputDir, "page_archive_rewritten.mht");
            Utils.writeStringToFile(rewrittenFile, rewrittenContent);
            runOnUiThread(() -> {
                Toast.makeText(pagedl.this, "Web Archive 保存＆書換完了：\n" + rewrittenFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                clearCacheAndCookies();
            });
        } catch (Exception e) {
            Log.e(TAG, "MIME書換エラー", e);
            runOnUiThread(() ->
                    Toast.makeText(pagedl.this, "MIME書換エラー：" + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            isSaving = false;
        }
    }

    private void saveWithoutJavaScript(String urlString, String siteName) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", ACCEPT_HEADER);
            conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error code: " + responseCode);
                runOnUiThread(() ->
                        Toast.makeText(pagedl.this, "HTTP error code: " + responseCode, Toast.LENGTH_LONG).show());
                return;
            }
            StringBuilder html = new StringBuilder();
            try (InputStream is = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line).append("\n");
                }
            }
            String htmlContent = html.toString();
            String pageTitle = extractTitle(htmlContent);
            String safePageTitle = pageTitle.replaceAll("[^a-zA-Z0-9]", "_");
            File outputDir = getOutputDirectory(siteName, safePageTitle);
            if (outputDir == null) {
                return;
            }
            if (localPathSwitch.isChecked()) {
                htmlContent = rewriteHtmlLocalPaths(htmlContent, urlString, outputDir);
            }
            File htmlFile = new File(outputDir, "page.html");
            Utils.writeStringToFile(htmlFile, htmlContent);
            if (htmlFile.exists()) {
                runOnUiThread(() -> {
                    Toast.makeText(pagedl.this, "HTML 保存完了：\n" + htmlFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    clearCacheAndCookies();
                });
            } else {
                runOnUiThread(() ->
                        Toast.makeText(pagedl.this, "HTML ファイルが存在しません", Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "HTML保存エラー", e);
            runOnUiThread(() ->
                    Toast.makeText(pagedl.this, "エラー：" + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            isSaving = false;
        }
    }

    private void clearCacheAndCookies() {
        webView.clearCache(true);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
    }

    @Override
    public void onBackPressed() {
        if (isSaving) {
            Toast.makeText(this, "保存中はバックキーが無効です", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    private String rewriteHtmlLocalPaths(String html, String baseUrl, File dir) {
        Pattern pattern = Pattern.compile("(?i)(src|href)\\s*=\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String attr = matcher.group(1);
            String originalUrl = matcher.group(2);
            try {
                URL resourceUrl = originalUrl.startsWith("http://") || originalUrl.startsWith("https://")
                        ? new URL(originalUrl)
                        : new URL(new URL(baseUrl), originalUrl);
                String fileName = new File(resourceUrl.getPath()).getName();
                if (fileName.isEmpty()) {
                    fileName = "index.html";
                }
                File resourceFile = new File(dir, fileName);
                downloadResource(resourceUrl.toString(), resourceFile);
                String localPath = "file://" + resourceFile.getAbsolutePath();
                matcher.appendReplacement(sb, attr + "=\"" + localPath + "\"");
            } catch (Exception e) {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void downloadResource(String resourceUrl, File destination) throws IOException {
        if (destination.exists()) {
            return;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(resourceUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String extractTitle(String html) {
        Pattern pattern = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "untitled";
    }

    private String getExtensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        if (mimeType.contains("html")) {
            return ".html";
        } else if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
            return ".jpg";
        } else if (mimeType.contains("png")) {
            return ".png";
        } else if (mimeType.contains("gif")) {
            return ".gif";
        } else if (mimeType.contains("pdf")) {
            return ".pdf";
        }
        return "";
    }

    private void saveDataUrl(String dataUrl, String siteName) {
        String datetime = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File baseDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "page");
        File outputDir = new File(baseDir, datetime);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            isSaving = false;
            return;
        }
        saveDataUrlContent(dataUrl, outputDir, datetime);
    }

    private void saveDataUrlContent(String dataUrl, File outputDir, String baseName) {
        try {
            int commaIndex = dataUrl.indexOf(",");
            if (commaIndex == -1) {
                throw new IOException("data URL の形式が不正です");
            }
            String header = dataUrl.substring(0, commaIndex);
            String base64Data = dataUrl.substring(commaIndex + 1);
            String mimeType = "application/octet-stream";
            Pattern pattern = Pattern.compile("data:([^;]+);base64");
            Matcher matcher = pattern.matcher(header);
            if (matcher.find()) {
                mimeType = matcher.group(1);
            }
            String extension = getExtensionForMimeType(mimeType);
            String fileName = baseName + extension;
            File outFile = new File(outputDir, fileName);
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(data);
            }
            runOnUiThread(() -> {
                Toast.makeText(pagedl.this, "保存完了：\n" + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                clearCacheAndCookies();
            });
        } catch (Exception e) {
            Log.e(TAG, "Data URL 保存エラー", e);
            runOnUiThread(() ->
                    Toast.makeText(pagedl.this, "Data URL 保存エラー：" + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            isSaving = false;
        }
    }

    private File getOutputDirectory(String siteName, String safePageTitle) {
        File baseDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "page");
        File outputDir = new File(baseDir, siteName + "(" + safePageTitle + ")/データ保存");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "フォルダ作成失敗: " + outputDir.getAbsolutePath());
            runOnUiThread(() ->
                    Toast.makeText(pagedl.this, "フォルダ作成失敗: " + outputDir.getAbsolutePath(), Toast.LENGTH_LONG).show());
            return null;
        }
        return outputDir;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}

class Utils {
    public static String readFileToString(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int readBytes = fis.read(data);
            if (readBytes != data.length) {
                throw new IOException("ファイル全体の読み込みに失敗しました");
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public static void writeStringToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}

class MimeParser {
    public static String rewriteContentLocations(String mhtContent, File dir) {
        Pattern pattern = Pattern.compile("(?im)^(Content-Location:\\s*)(http[s]?://\\S+)$");
        Matcher matcher = pattern.matcher(mhtContent);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String originalUrl = matcher.group(2).trim();
            try {
                URL url = new URL(originalUrl);
                String fileName = new File(url.getPath()).getName();
                if (fileName.isEmpty()) {
                    fileName = "index.html";
                }
                String localPath = "file://" + dir.getAbsolutePath() + "/" + fileName;
                String replacement = matcher.group(1) + localPath;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } catch (Exception e) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
