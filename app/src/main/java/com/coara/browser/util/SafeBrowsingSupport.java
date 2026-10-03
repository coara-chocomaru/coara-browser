package com.coara.browser.util;

import android.app.Activity;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.SafeBrowsingResponseCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SafeBrowsingSupport {
    private SafeBrowsingSupport() {
    }

    @SuppressWarnings("deprecation")
    public static void initialize(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
                WebViewCompat.startSafeBrowsing(activity, success -> { });
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean handle(Activity activity, WebView view, WebResourceRequest request,
                                 int threatType, SafeBrowsingResponseCompat response) {
        if (response == null) {
            return true;
        }
        if (request != null && !request.isForMainFrame()) {
            backToSafety(response);
            return true;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            backToSafety(response);
            return true;
        }

        String url = request != null && request.getUrl() != null
                ? request.getUrl().toString()
                : safeUrl(view);
        String host = "";
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
        } catch (Throwable ignored) {
        }

        String reason = reasonFor(threatType);
        String detail = detailFor(threatType);
        StringBuilder message = new StringBuilder();
        message.append("WebView の安全確認で、既知の危険なページとして検出されました。\n\n");
        message.append("判定理由: ").append(reason).append("\n");
        message.append(detail).append("\n\n");
        if (!host.isEmpty()) {
            message.append("対象サイト: ").append(host).append("\n");
        }
        if (url != null && !url.isEmpty()) {
            message.append("URL: ").append(url).append("\n\n");
        }
        message.append("前のページへ戻る場合、このページは読み込みを続行しません。\n");
        message.append("このページを開く場合は、危険性を理解した上で安全確認を無視して表示を続行します。パスワード、認証情報、カード情報などの入力は慎重に判断してください。");

        final boolean[] decided = {false};
        try {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("危険なページを検出")
                    .setMessage(message.toString())
                    .setNegativeButton("前のページへ戻る", (dialog, which) -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        backToSafety(response);
                    })
                    .setPositiveButton("このページを開く", (dialog, which) -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        proceed(response);
                    })
                    .setOnCancelListener(dialog -> {
                        if (decided[0]) {
                            return;
                        }
                        decided[0] = true;
                        backToSafety(response);
                    })
                    .show();
        } catch (Throwable error) {
            backToSafety(response);
            try {
                Toast.makeText(activity, "危険なページをブロックしました", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static String safeUrl(WebView view) {
        if (view == null) {
            return "";
        }
        try {
            String url = view.getUrl();
            return url == null ? "" : url;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void backToSafety(SafeBrowsingResponseCompat response) {
        try {
            response.backToSafety(true);
        } catch (Throwable ignored) {
        }
    }

    private static void proceed(SafeBrowsingResponseCompat response) {
        try {
            response.proceed(true);
        } catch (Throwable ignored) {
        }
    }

    private static String reasonFor(int threatType) {
        switch (threatType) {
            case WebViewClient.SAFE_BROWSING_THREAT_MALWARE:
                return "マルウェアの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_PHISHING:
                return "フィッシングの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE:
                return "望ましくないソフトウェアの可能性";
            case WebViewClient.SAFE_BROWSING_THREAT_BILLING:
                return "不正または欺瞞的な課金につながる可能性";
            default:
                return "既知の危険なコンテンツとして検出";
        }
    }

    private static String detailFor(int threatType) {
        switch (threatType) {
            case WebViewClient.SAFE_BROWSING_THREAT_MALWARE:
                return "このサイトでは、端末やデータに被害を与えるマルウェアの配布が確認されている可能性があります。不要なアプリやファイルのインストールを求められる場合があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_PHISHING:
                return "正規サイトを装って、ID、パスワード、認証コード、カード情報などを入力させるフィッシングの危険がある可能性があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE:
                return "ブラウザや端末の設定を望ましくない形に変更したり、不要なソフトウェアをインストールさせたりする危険がある可能性があります。";
            case WebViewClient.SAFE_BROWSING_THREAT_BILLING:
                return "不透明な請求、意図しない定期購入、欺瞞的な支払い誘導などにつながる危険がある可能性があります。";
            default:
                return "WebView の安全確認サービスが、このURLを安全に表示し続けることが望ましくないと判定しました。";
        }
    }
}
