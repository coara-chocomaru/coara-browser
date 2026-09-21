package com.coara.browser.util;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;

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

    public static boolean isDeviceSecure(Context context) {
        try {
            KeyguardManager keyguardManager =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager == null) {
                return false;
            }
            return keyguardManager.isDeviceSecure();
        } catch (Exception e) {
            return false;
        }
    }

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

    public static void resetSession() {
        unlockedThisSession = false;
    }
}
