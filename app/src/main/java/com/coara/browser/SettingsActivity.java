package com.coara.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import io.noties.markwon.Markwon;

public class SettingsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SettingsAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        List<SettingItem> settingsList = new ArrayList<>();
        settingsList.add(new SettingItem("アプリ情報", "アプリの情報", this::openAppInfo));
        settingsList.add(new SettingItem("端末情報", "デバイスの(getprop)情報を表示", this::showDeviceInfo));
        settingsList.add(new SettingItem("アプリバージョン", getAppVersion(), null));
        settingsList.add(new SettingItem("ライセンス", "利用規約を表示", this::showLicense));

        adapter = new SettingsAdapter(settingsList);
        recyclerView.setAdapter(adapter);
    }

    private String getAppVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "不明";
        }
    }

    private void openAppInfo() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void showDeviceInfo() {
    StringBuilder result = new StringBuilder();
    try {
        Process process = Runtime.getRuntime().exec("getprop");
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        String[][] props = {
                {"ro.product.model", "model"},
                {"ro.product.manufacturer", "manufacturer"},
                {"ro.product.brand", "carrier"},
                {"ro.system.build.id", "Build id"},
                {"ro.system.build.version.release", "OS version"},
                {"ro.vndk.version", "VNDK"},
                {"ro.system.build.version.sdk", "SDK"},
                {"ro.hardware", "SoC"},
                {"ro.build.type", "Build Type"},
                {"ro.product.locale", "Language"},
                {"ro.sf.lcd_density", "Density"},
                {"ro.boot.baseband", "baseband"},
                {"ro.boot.slot_suffix", "slot"}
        };

        java.util.Map<String, String> propValues = new java.util.HashMap<>();

        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("[")) continue; 
            int keyStart = line.indexOf('[') + 1;
            int keyEnd = line.indexOf(']');
            int valueStart = line.indexOf('[', keyEnd) + 1;
            int valueEnd = line.indexOf(']', valueStart);

            if (keyStart < 0 || keyEnd < 0 || valueStart < 0 || valueEnd < 0) continue;
            String key = line.substring(keyStart, keyEnd).trim();
            String value = line.substring(valueStart, valueEnd).trim();

            if (key.startsWith("ro.")) {
                propValues.put(key, value);
            }
        }
        reader.close();

        for (String[] prop : props) {
            String val = propValues.getOrDefault(prop[0], "不明");
            result.append(prop[1]).append("  ").append(val).append("\n");
        }

    } catch (Exception e) {
        result.append("取得失敗");
    }
    showTextDialog("端末情報", result.toString());
}

    private void showLicense() {
        StringBuilder licenseText = new StringBuilder();
        try {
            InputStream inputStream = getAssets().open("LICENSE.MD");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                licenseText.append(line).append("\n");
            }
            reader.close();
        } catch (Exception e) {
            licenseText.append("ライセンス情報を取得できません");
        }
        showMarkdownDialog("ライセンス情報", licenseText.toString());
    }

    private void showTextDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton("閉じる", null);
        builder.show();
    }

    private void showMarkdownDialog(String title, String markdown) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_markdown, null);
        TextView textView = dialogView.findViewById(R.id.markdownText);

        Markwon markwon = Markwon.create(this);
        markwon.setMarkdown(textView, markdown);

        builder.setView(dialogView);
        builder.setPositiveButton("閉じる", null);
        builder.show();
    }
}



