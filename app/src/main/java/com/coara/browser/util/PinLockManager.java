package com.coara.browser.util;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 「端末のPINを使用」機能まわりの共通処理。
 *
 * - 設定のON/OFFは SharedPreferences に保存する（KEY_PIN_LOCK_ENABLED）。
 * - 実際にロックが必要かどうかは「設定がONであること」に加えて
 *   「その時点で端末にPIN/パターン/パスワード等が設定されていること」を都度確認する。
 *   端末側で後からロック解除された場合に、アプリがユーザーを締め出してしまわないようにするため。
 * - アンロック状態はプロセス生存中だけ保持する（アプリを完全に終了すれば再度ロックされる、
 *   一般的なアプリロックの挙動）。画面回転や他のアクティビティへの遷移では再度は問わない。
 */
public final class PinLockManager {

    private static volatile boolean unlockedThisSession = false;

    private PinLockManager() {
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        return pref.getBoolean(BrowserConstants.KEY_PIN_LOCK_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences pref = context.getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);
        pref.edit().putBoolean(BrowserConstants.KEY_PIN_LOCK_ENABLED, enabled).apply();
    }

    /**
     * 端末にPIN・パターン・パスワード・生体認証等、何らかのロック方法が設定済みかどうか。
     */
    public static boolean isDeviceSecure(Context context) {
        try {
            KeyguardManager keyguardManager =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return keyguardManager.isDeviceSecure();
            }
            //noinspection deprecation
            return keyguardManager.isKeyguardSecure();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * この機能で実際にロック画面（端末の認証確認）を要求すべきかどうか。
     * 設定がONでも、端末側にPINが設定されていなければロックしない（締め出し防止）。
     */
    public static boolean shouldPromptLock(Context context) {
        if (unlockedThisSession) {
            return false;
        }
        if (!isEnabled(context)) {
            return false;
        }
        return isDeviceSecure(context);
    }

    public static boolean isUnlockedThisSession() {
        return unlockedThisSession;
    }

    public static void markUnlocked() {
        unlockedThisSession = true;
    }

    /**
     * テスト/ログアウト用途などで再ロックしたい場合に呼ぶ（現状のUIからは未使用）。
     */
    public static void resetSession() {
        unlockedThisSession = false;
    }
}
