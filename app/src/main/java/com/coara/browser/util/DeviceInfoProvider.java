package com.coara.browser.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public final class DeviceInfoProvider {
    private DeviceInfoProvider() {}

    public static String getAppVersion(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "不明";
        }
    }

    public static Intent createAppInfoIntent(Context context) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static CharSequence collectDeviceInfo() {
        SpannableStringBuilder result = new SpannableStringBuilder();
        final String[][] props = {
                {"ro.product.model", "model"},
                {"ro.product.manufacturer", "manufacturer"},
                {"ro.product.brand", "carrier"},
                {"ro.system.build.id", "Build id"},
                {"ro.system.build.version.release", "OS version"},
                {"ro.vndk.version", "VNDK"},
                {"ro.system.build.version.sdk", "SDK"},
                {"ro.hardware", "soc"},
                {"ro.build.type", "Build Type"},
                {"ro.product.locale", "Language"},
                {"ro.sf.lcd_density", "Density"},
                {"ro.boot.baseband", "baseband"},
                {"ro.boot.slot_suffix", "slot"}
        };

        try {
            Process process = Runtime.getRuntime().exec("getprop");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                Map<String, String> propValues = new HashMap<>(props.length * 2);
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("[")) continue;
                    int keyStart = line.indexOf('[') + 1;
                    int keyEnd = line.indexOf(']');
                    if (keyEnd <= keyStart) continue;
                    int valueStart = line.indexOf('[', keyEnd) + 1;
                    int valueEnd = line.indexOf(']', valueStart);
                    if (valueStart <= 0 || valueEnd <= valueStart) continue;

                    String key = line.substring(keyStart, keyEnd).trim();
                    String value = line.substring(valueStart, valueEnd).trim();
                    if (key.startsWith("ro.")) {
                        propValues.put(key, value);
                    }
                }

                for (String[] prop : props) {
                    String label = prop[1] + "  ";
                    String val = propValues.getOrDefault(prop[0], "不明");
                    result.append(label);
                    int start = result.length();
                    result.append(val).append("\n");
                    int end = result.length();
                    result.setSpan(new ForegroundColorSpan(0xFF448AFF), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        } catch (Exception e) {
            result.append("取得失敗");
        }
        return result;
    }

    public static String readAssetText(Context context, String assetName) {
        StringBuilder sb = new StringBuilder();
        try (InputStream inputStream = context.getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            sb.append("ライセンス情報を取得できません");
        }
        return sb.toString();
    }
}
