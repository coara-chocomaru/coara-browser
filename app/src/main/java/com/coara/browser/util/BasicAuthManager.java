package com.coara.browser.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class BasicAuthManager {
    public static final String EXTRA_BASIC_AUTH_ENABLED = "com.coara.browser.EXTRA_BASIC_AUTH_ENABLED";
    public interface CredentialsCallback {
        void onCredentials(String username, String password);
    }

    private static final class Credentials {
        final String username;
        final String password;

        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private static final class AuthRequest {
        final Activity activity;
        final WebView webView;
        final HttpAuthHandler handler;

        AuthRequest(Activity activity, WebView webView, HttpAuthHandler handler) {
            this.activity = activity;
            this.webView = webView;
            this.handler = handler;
        }
    }

    private static final class CredentialRequest {
        final Activity activity;
        final WebView webView;
        final CredentialsCallback callback;

        CredentialRequest(Activity activity, WebView webView, CredentialsCallback callback) {
            this.activity = activity;
            this.webView = webView;
            this.callback = callback;
        }
    }

    private static final class PendingDialog {
        final String key;
        final String host;
        final String realm;
        final Activity activity;
        final ArrayList<AuthRequest> authRequests = new ArrayList<>();
        final ArrayList<CredentialRequest> credentialRequests = new ArrayList<>();
        android.app.Dialog dialog;

        PendingDialog(String key, String host, String realm, Activity activity) {
            this.key = key;
            this.host = host;
            this.realm = realm;
            this.activity = activity;
        }
    }

    private static final class ScopedCredentials {
        final String scheme;
        final String host;
        final int port;
        final String pathPrefix;
        final Credentials credentials;

        ScopedCredentials(String scheme, String host, int port, String pathPrefix, Credentials credentials) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.pathPrefix = pathPrefix;
            this.credentials = credentials;
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<String, Credentials> MEMORY = new HashMap<>();
    private static final Map<String, PendingDialog> PENDING = new HashMap<>();
    private static final ArrayList<ScopedCredentials> SCOPED = new ArrayList<>();
    private static final Set<String> INVALIDATED_HOSTS = new HashSet<>();

    private BasicAuthManager() {
    }

    public static void handleHttpAuthRequest(
            Activity activity,
            WebView view,
            HttpAuthHandler handler,
            boolean enabled,
            String host,
            String realm) {
        Runnable task = () -> {
            if (!enabled || activity == null || handler == null) {
                cancel(handler);
                return;
            }
            if (isActivityUnavailable(activity)) {
                cancel(handler);
                return;
            }

            String normalizedHost = normalizeHost(host);
            String normalizedRealm = realm == null ? "" : realm;
            Credentials stored = getStoredCredentials(activity, view, normalizedHost, normalizedRealm);
            if (stored != null) {
                rememberScoped(view, normalizedHost, normalizedRealm, stored);
                proceed(handler, stored);
                return;
            }

            String key = buildPendingKey(activity, normalizedHost, normalizedRealm);
            PendingDialog pending;
            synchronized (LOCK) {
                pending = PENDING.get(key);
                if (pending == null) {
                    pending = new PendingDialog(key, normalizedHost, normalizedRealm, activity);
                    PENDING.put(key, pending);
                }
                pending.authRequests.add(new AuthRequest(activity, view, handler));
            }
            if (pending.dialog == null) {
                showDialog(pending);
            }
        };
        runOnMain(activity, task);
    }

    public static void requestCredentials(
            Activity activity,
            WebView view,
            String host,
            String realm,
            CredentialsCallback callback) {
        if (activity == null || callback == null) {
            return;
        }
        Runnable task = () -> {
            if (isActivityUnavailable(activity)) {
                callback.onCredentials(null, null);
                return;
            }
            String normalizedHost = normalizeHost(host);
            String normalizedRealm = realm == null ? "" : realm;
            Credentials stored = getStoredCredentials(activity, view, normalizedHost, normalizedRealm);
            if (stored != null) {
                rememberScoped(view, normalizedHost, normalizedRealm, stored);
                callback.onCredentials(stored.username, stored.password);
                return;
            }
            String key = buildPendingKey(activity, normalizedHost, normalizedRealm);
            PendingDialog pending;
            synchronized (LOCK) {
                pending = PENDING.get(key);
                if (pending == null) {
                    pending = new PendingDialog(key, normalizedHost, normalizedRealm, activity);
                    PENDING.put(key, pending);
                }
                pending.credentialRequests.add(new CredentialRequest(activity, view, callback));
            }
            if (pending.dialog == null) {
                showDialog(pending);
            }
        };
        runOnMain(activity, task);
    }

    public static void markAuthenticationFailure(String host) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            INVALIDATED_HOSTS.add(normalizedHost);
            MEMORY.keySet().removeIf(key -> key.startsWith(normalizedHost + "\u0000"));
            SCOPED.removeIf(record -> normalizedHost.equals(record.host));
        }
    }

    public static String getAuthorizationHeaderForUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URL target = new URL(url);
            String scheme = target.getProtocol().toLowerCase();
            String host = normalizeHost(target.getHost());
            int port = target.getPort() != -1 ? target.getPort() : target.getDefaultPort();
            String path = target.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            synchronized (LOCK) {
                if (INVALIDATED_HOSTS.contains(host)) {
                    return null;
                }
                Credentials selected = null;
                String selectedHeader = null;
                for (ScopedCredentials record : SCOPED) {
                    if (!scheme.equals(record.scheme) || !host.equals(record.host) || port != record.port) {
                        continue;
                    }
                    if (!pathMatches(path, record.pathPrefix)) {
                        continue;
                    }
                    String header = buildAuthorizationHeader(record.credentials);
                    if (selectedHeader == null) {
                        selected = record.credentials;
                        selectedHeader = header;
                    } else if (!selected.username.equals(record.credentials.username)
                            || !selected.password.equals(record.credentials.password)) {
                        return null;
                    }
                }
                return selected != null ? selectedHeader : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            for (PendingDialog pending : PENDING.values()) {
                cancelPending(pending);
            }
            PENDING.clear();
            MEMORY.clear();
            SCOPED.clear();
            INVALIDATED_HOSTS.clear();
        }
        if (context == null) {
            return;
        }
        Runnable task = () -> {
            try {
                WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword();
            } catch (Exception ignored) {
            }
        };
        runOnMain(context instanceof Activity ? (Activity) context : null, task);
    }

    public static void cancelPendingForActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        Runnable task = () -> {
            ArrayList<String> removeKeys = new ArrayList<>();
            synchronized (LOCK) {
                for (Map.Entry<String, PendingDialog> entry : PENDING.entrySet()) {
                    PendingDialog pending = entry.getValue();
                    for (int i = pending.authRequests.size() - 1; i >= 0; i--) {
                        AuthRequest request = pending.authRequests.get(i);
                        if (request.activity == activity) {
                            cancel(request.handler);
                            pending.authRequests.remove(i);
                        }
                    }
                    for (int i = pending.credentialRequests.size() - 1; i >= 0; i--) {
                        CredentialRequest request = pending.credentialRequests.get(i);
                        if (request.activity == activity) {
                            try {
                                request.callback.onCredentials(null, null);
                            } catch (Exception ignored) {
                            }
                            pending.credentialRequests.remove(i);
                        }
                    }
                    if (pending.authRequests.isEmpty() && pending.credentialRequests.isEmpty()) {
                        removeKeys.add(entry.getKey());
                        dismiss(pending);
                    }
                }
                for (String key : removeKeys) {
                    PENDING.remove(key);
                }
            }
        };
        runOnMain(activity, task);
    }

    private static Credentials getStoredCredentials(
            Activity activity,
            WebView view,
            String host,
            String realm) {
        if (host.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            if (INVALIDATED_HOSTS.contains(host)) {
                return null;
            }
            Credentials memory = MEMORY.get(buildCredentialKey(host, realm));
            if (memory != null) {
                return memory;
            }
        }
        try {
            String[] stored;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stored = WebViewDatabase.getInstance(activity).getHttpAuthUsernamePassword(host, realm);
            } else if (view != null) {
                stored = view.getHttpAuthUsernamePassword(host, realm);
            } else {
                stored = null;
            }
            if (stored != null && stored.length >= 2 && stored[0] != null && stored[1] != null) {
                Credentials credentials = new Credentials(stored[0], stored[1]);
                synchronized (LOCK) {
                    if (!INVALIDATED_HOSTS.contains(host)) {
                        MEMORY.put(buildCredentialKey(host, realm), credentials);
                    }
                }
                return credentials;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void showDialog(PendingDialog pending) {
        Activity activity = pending.activity;
        if (isActivityUnavailable(activity)) {
            completePending(pending, null);
            return;
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        EditText usernameInput = new EditText(activity);
        usernameInput.setHint("ユーザー名");
        usernameInput.setSingleLine(true);
        usernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        layout.addView(usernameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText passwordInput = new EditText(activity);
        passwordInput.setHint("パスワード");
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        passwordParams.topMargin = padding / 2;
        layout.addView(passwordInput, passwordParams);

        String title = pending.host.isEmpty() ? "Basic認証情報を入力" : "Basic認証情報を入力\n" + pending.host;
        if (!pending.realm.isEmpty()) {
            title += "\n" + pending.realm;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("ログイン", (dialog, which) -> {
                    completePending(pending, new Credentials(
                            usernameInput.getText().toString(),
                            passwordInput.getText().toString()));
                })
                .setNegativeButton("キャンセル", (dialog, which) -> completePending(pending, null));

        android.app.Dialog dialog = builder.create();
        pending.dialog = dialog;
        dialog.setOnCancelListener(value -> completePending(pending, null));
        dialog.setOnShowListener(value -> {
            try {
                usernameInput.requestFocus();
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            } catch (Exception ignored) {
            }
        });
        try {
            dialog.show();
        } catch (Exception e) {
            pending.dialog = null;
            completePending(pending, null);
        }
    }

    private static void completePending(PendingDialog pending, Credentials credentials) {
        ArrayList<AuthRequest> authRequests;
        ArrayList<CredentialRequest> credentialRequests;
        synchronized (LOCK) {
            PendingDialog current = PENDING.remove(pending.key);
            if (current == null) {
                return;
            }
            authRequests = new ArrayList<>(current.authRequests);
            credentialRequests = new ArrayList<>(current.credentialRequests);
            current.authRequests.clear();
            current.credentialRequests.clear();
        }

        dismiss(pending);

        if (credentials == null) {
            for (AuthRequest request : authRequests) {
                cancel(request.handler);
            }
            for (CredentialRequest request : credentialRequests) {
                try {
                    request.callback.onCredentials(null, null);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        synchronized (LOCK) {
            MEMORY.put(buildCredentialKey(pending.host, pending.realm), credentials);
            INVALIDATED_HOSTS.remove(pending.host);
        }
        for (AuthRequest request : authRequests) {
            rememberScoped(request.webView, pending.host, pending.realm, credentials);
            persistCredentials(request.activity, request.webView, pending.host, pending.realm, credentials);
            proceed(request.handler, credentials);
        }
        for (CredentialRequest request : credentialRequests) {
            rememberScoped(request.webView, pending.host, pending.realm, credentials);
            persistCredentials(request.activity, request.webView, pending.host, pending.realm, credentials);
            try {
                request.callback.onCredentials(credentials.username, credentials.password);
            } catch (Exception ignored) {
            }
        }
    }

    private static void rememberScoped(WebView view, String host, String realm, Credentials credentials) {
        if (view == null || credentials == null || host.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            try {
                String url = view.getUrl();
                if (url == null || url.isEmpty()) {
                    return;
                }
                URL source = new URL(url);
                String scheme = source.getProtocol().toLowerCase();
                String sourceHost = normalizeHost(source.getHost());
                if (!host.equals(sourceHost)) {
                    return;
                }
                int port = source.getPort() != -1 ? source.getPort() : source.getDefaultPort();
                String path = source.getPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                String pathPrefix = directoryPrefix(path);
                ScopedCredentials record = new ScopedCredentials(
                        scheme, host, port, pathPrefix, credentials);
                synchronized (LOCK) {
                    for (int i = SCOPED.size() - 1; i >= 0; i--) {
                        ScopedCredentials existing = SCOPED.get(i);
                        if (scheme.equals(existing.scheme)
                                && host.equals(existing.host)
                                && port == existing.port
                                && pathPrefix.equals(existing.pathPrefix)) {
                            SCOPED.remove(i);
                        }
                    }
                    SCOPED.add(record);
                }
            } catch (Exception ignored) {
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private static void persistCredentials(
            Activity activity,
            WebView view,
            String host,
            String realm,
            Credentials credentials) {
        if (activity == null || credentials == null || host.isEmpty()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebViewDatabase.getInstance(activity).setHttpAuthUsernamePassword(
                        host, realm, credentials.username, credentials.password);
            } else if (view != null) {
                view.setHttpAuthUsernamePassword(host, realm, credentials.username, credentials.password);
            }
        } catch (Exception ignored) {
        }
    }

    private static void cancelPending(PendingDialog pending) {
        for (AuthRequest request : pending.authRequests) {
            cancel(request.handler);
        }
        for (CredentialRequest request : pending.credentialRequests) {
            try {
                request.callback.onCredentials(null, null);
            } catch (Exception ignored) {
            }
        }
        pending.authRequests.clear();
        pending.credentialRequests.clear();
        dismiss(pending);
    }

    private static void dismiss(PendingDialog pending) {
        android.app.Dialog dialog = pending.dialog;
        pending.dialog = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private static void cancel(HttpAuthHandler handler) {
        if (handler == null) {
            return;
        }
        try {
            handler.cancel();
        } catch (Exception ignored) {
        }
    }

    private static void proceed(HttpAuthHandler handler, Credentials credentials) {
        if (handler == null || credentials == null) {
            return;
        }
        try {
            handler.proceed(credentials.username, credentials.password);
        } catch (Exception ignored) {
        }
    }

    private static void runOnMain(Activity activity, Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else if (activity != null) {
            try {
                activity.runOnUiThread(task);
            } catch (Exception ignored) {
                MAIN_HANDLER.post(task);
            }
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private static boolean isActivityUnavailable(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return true;
        }
        return false;
    }

    private static String buildPendingKey(Activity activity, String host, String realm) {
        return System.identityHashCode(activity) + "\u0000" + buildCredentialKey(host, realm);
    }

    private static String buildCredentialKey(String host, String realm) {
        return host + "\u0000" + realm;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase();
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String directoryPrefix(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            return "/";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return path.substring(0, lastSlash + 1);
    }

    private static boolean pathMatches(String path, String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) {
            return true;
        }
        if (!path.startsWith(prefix)) {
            return false;
        }
        return true;
    }

    public static String buildAuthorizationHeader(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        return buildAuthorizationHeader(new Credentials(username, password));
    }

    private static String buildAuthorizationHeader(Credentials credentials) {
        if (credentials == null) {
            return null;
        }
        String value = credentials.username + ":" + credentials.password;
        String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "Basic " + encoded;
    }
}
