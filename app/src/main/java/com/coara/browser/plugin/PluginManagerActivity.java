package com.coara.browser.plugin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.coara.browser.R;
import com.coara.browser.util.UiThread;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PluginManagerActivity extends AppCompatActivity implements PluginManager.Listener {
    private static final int LOG_VIEW_CHARS = 200_000;

    private PluginManager manager;
    private final List<PluginInfo> items = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private PluginAdapter adapter;
    private TextView emptyView;
    private ActivityResultLauncher<Intent> pickLauncher;
    private ActivityResultLauncher<Intent> saveLauncher;
    private String saveScopeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugin_manager);
        manager = PluginManager.get(this);

        ListView listView = findViewById(R.id.pluginListView);
        emptyView = findViewById(R.id.pluginEmptyText);
        adapter = new PluginAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) {
                showDetails(items.get(position));
            }
        });

        pickLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                this::onPickResult);
        saveLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                this::onSaveResult);

        findViewById(R.id.pluginInstallButton).setOnClickListener(v -> startPick());
        findViewById(R.id.pluginLogButton).setOnClickListener(v -> showLogDialog(null));
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        manager.removeListener(this);
        super.onStop();
    }

    @Override
    public void onPluginsChanged() {
        refresh();
    }

    @Override
    public void onPluginAutoDisabled(PluginInfo info, String reason) {
        refresh();
    }

    private void refresh() {
        manager.reloadIfChanged();
        List<PluginInfo> latest = manager.list();
        items.clear();
        items.addAll(latest);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        loadIconsAsync(latest);
    }

    private static String iconKey(PluginInfo p) {
        return p.id + "@" + p.installedAt;
    }

    private void loadIconsAsync(final List<PluginInfo> list) {
        final List<PluginInfo> missing = new ArrayList<>();
        for (PluginInfo p : list) {
            if (!p.icon.isEmpty() && !icons.containsKey(iconKey(p))) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        UiThread.io().execute(() -> {
            final Map<String, Bitmap> loaded = new HashMap<>();
            for (PluginInfo p : missing) {
                File f = manager.iconFile(p);
                if (f == null) {
                    continue;
                }
                Bitmap bm = decodeIcon(f);
                if (bm != null) {
                    loaded.put(iconKey(p), bm);
                }
            }
            if (loaded.isEmpty()) {
                return;
            }
            runOnUiThread(() -> {
                icons.putAll(loaded);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private static Bitmap decodeIcon(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > 192) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void startPick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            pickLauncher.launch(i);
        } catch (Exception e) {
            Toast.makeText(this, "ファイル選択を開始できません", Toast.LENGTH_SHORT).show();
        }
    }

    private void onPickResult(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        final Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }
        Toast.makeText(this, "zipを検証しています…", Toast.LENGTH_SHORT).show();
        UiThread.io().execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                final ParsedPlugin parsed = PluginInstaller.parse(in);
                runOnUiThread(() -> confirmInstall(parsed));
            } catch (PluginInstallException e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> showMessage("インストールできません", msg));
            } catch (Throwable t) {
                final String msg = String.valueOf(t.getMessage());
                runOnUiThread(() -> showMessage("インストールできません", "zipを読み込めませんでした\n" + msg));
            }
        });
    }

    private void confirmInstall(final ParsedPlugin parsed) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        PluginInfo info = parsed.info;
        StringBuilder sb = new StringBuilder();
        sb.append("名前: ").append(info.title).append('\n');
        if (!info.version.isEmpty()) {
            sb.append("バージョン: ").append(info.version).append('\n');
        }
        if (!info.author.isEmpty()) {
            sb.append("作者: ").append(info.author).append('\n');
        }
        if (!info.description.isEmpty()) {
            sb.append("説明: ").append(info.description).append('\n');
        }
        sb.append("実行タイミング: ").append(info.runAt).append('\n');
        sb.append("対象: ").append(join(PluginPatterns.effectiveMatches(info.matches))).append('\n');
        if (!info.excludes.isEmpty()) {
            sb.append("除外: ").append(join(info.excludes)).append('\n');
        }
        sb.append("全フレーム: ").append(info.allFrames ? "はい" : "いいえ").append('\n');
        sb.append("js: ").append(join(info.scripts)).append('\n');
        if (!info.styles.isEmpty()) {
            sb.append("css: ").append(join(info.styles)).append('\n');
        }
        if (!parsed.warnings.isEmpty()) {
            sb.append("\n注意:\n");
            for (String w : parsed.warnings) {
                sb.append("・").append(w).append('\n');
            }
        }
        sb.append("\nこの拡張機能は閲覧中のページ内でJavaScriptを実行します。信頼できる提供元のものだけをインストールしてください。");
        new AlertDialog.Builder(this)
                .setTitle("インストールしますか?")
                .setMessage(sb.toString())
                .setPositiveButton("インストール", (d, w) -> doInstall(parsed))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void doInstall(final ParsedPlugin parsed) {
        UiThread.io().execute(() -> {
            try {
                final PluginManager.InstallOutcome outcome = manager.install(parsed);
                runOnUiThread(() -> {
                    refresh();
                    Toast.makeText(this, "「" + outcome.info.title + "」を"
                            + (outcome.updated ? "更新" : "インストール") + "しました", Toast.LENGTH_LONG).show();
                });
            } catch (PluginInstallException e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> showMessage("インストールできません", msg));
            } catch (Throwable t) {
                final String msg = String.valueOf(t.getMessage());
                runOnUiThread(() -> showMessage("インストールできません", msg));
            }
        });
    }

    private void showMessage(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("閉じる", null)
                .show();
    }

    private void showDetails(PluginInfo p) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN);
        StringBuilder sb = new StringBuilder();
        sb.append("名前: ").append(p.title).append('\n');
        sb.append("ID: ").append(p.id).append('\n');
        sb.append("状態: ").append(p.enabled ? "有効" : "無効").append('\n');
        if (!p.version.isEmpty()) {
            sb.append("バージョン: ").append(p.version).append('\n');
        }
        if (!p.author.isEmpty()) {
            sb.append("作者: ").append(p.author).append('\n');
        }
        if (!p.description.isEmpty()) {
            sb.append("説明: ").append(p.description).append('\n');
        }
        sb.append("実行タイミング: ").append(p.runAt).append('\n');
        sb.append("全フレーム: ").append(p.allFrames ? "はい" : "いいえ").append('\n');
        sb.append("対象: ").append(join(PluginPatterns.effectiveMatches(p.matches))).append('\n');
        if (!p.excludes.isEmpty()) {
            sb.append("除外: ").append(join(p.excludes)).append('\n');
        }
        sb.append("js: ").append(join(p.scripts)).append('\n');
        if (!p.styles.isEmpty()) {
            sb.append("css: ").append(join(p.styles)).append('\n');
        }
        sb.append("インストール: ").append(df.format(new Date(p.installedAt))).append('\n');
        sb.append("エラー回数: ").append(p.errorCount).append('\n');
        sb.append("クラッシュ回数: ").append(p.crashCount).append('\n');
        if (p.lastErrorAt > 0L) {
            sb.append("最終エラー: ").append(df.format(new Date(p.lastErrorAt))).append('\n');
            sb.append(p.lastError).append('\n');
        }
        if (!p.enabled && !p.disabledReason.isEmpty()) {
            sb.append("無効の理由: ").append(p.disabledReason).append('\n');
        }
        new AlertDialog.Builder(this)
                .setTitle(p.title)
                .setMessage(sb.toString())
                .setPositiveButton("閉じる", null)
                .show();
    }

    private void confirmDelete(final PluginInfo p) {
        new AlertDialog.Builder(this)
                .setTitle("削除")
                .setMessage("「" + p.title + "」を削除しますか?\n保存データも削除されます。")
                .setPositiveButton("削除", (d, w) -> {
                    manager.delete(p.id);
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showLogDialog(final String idOrNull) {
        String text = idOrNull == null ? manager.readLog(LOG_VIEW_CHARS) : manager.readLogFor(idOrNull, LOG_VIEW_CHARS);
        if (text.isEmpty()) {
            text = "ログはありません";
        }
        final ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(idOrNull == null ? "全ログ" : "ログ: " + idOrNull)
                .setView(scroll)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("保存", (d, w) -> startSave(idOrNull))
                .setNegativeButton("全消去", (d, w) -> confirmClearLog())
                .show();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("ログ消去")
                .setMessage("すべての拡張機能ログを消去しますか?")
                .setPositiveButton("消去", (d, w) -> {
                    manager.clearLog();
                    Toast.makeText(this, "ログを消去しました", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void startSave(String scopeId) {
        saveScopeId = scopeId;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "coara_plugin_log_" + (scopeId == null ? "all" : scopeId) + "_" + stamp + ".txt");
        try {
            saveLauncher.launch(i);
        } catch (Exception e) {
            Toast.makeText(this, "保存先を選択できません", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSaveResult(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        final Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }
        final String scope = saveScopeId;
        UiThread.io().execute(() -> {
            boolean ok = false;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    if (scope == null) {
                        manager.exportLog(os);
                    } else {
                        os.write(manager.readLogFor(scope, 2_000_000).getBytes(StandardCharsets.UTF_8));
                    }
                    os.flush();
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
            final boolean success = ok;
            runOnUiThread(() -> Toast.makeText(this, success ? "ログを保存しました" : "ログを保存できませんでした",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "(なし)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private final class PluginAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(PluginManagerActivity.this).inflate(R.layout.item_plugin, parent, false);
                h = new Holder();
                h.icon = row.findViewById(R.id.pluginIcon);
                h.title = row.findViewById(R.id.pluginTitle);
                h.meta = row.findViewById(R.id.pluginMeta);
                h.description = row.findViewById(R.id.pluginDescription);
                h.status = row.findViewById(R.id.pluginStatus);
                h.toggle = row.findViewById(R.id.pluginSwitch);
                h.menu = row.findViewById(R.id.pluginMenu);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            final PluginInfo p = items.get(position);
            Bitmap bm = icons.get(iconKey(p));
            if (bm != null) {
                h.icon.setImageBitmap(bm);
            } else {
                h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            h.title.setText(p.title);
            StringBuilder meta = new StringBuilder();
            if (!p.version.isEmpty()) {
                meta.append('v').append(p.version).append("  ");
            }
            if (!p.author.isEmpty()) {
                meta.append(p.author).append("  ");
            }
            meta.append(p.runAt);
            if (p.allFrames) {
                meta.append("  全フレーム");
            }
            h.meta.setText(meta.toString());
            if (p.description.isEmpty()) {
                h.description.setVisibility(View.GONE);
            } else {
                h.description.setVisibility(View.VISIBLE);
                h.description.setText(p.description);
            }
            String status = null;
            if (!p.enabled && !p.disabledReason.isEmpty()) {
                status = "無効: " + p.disabledReason;
            } else if (p.errorCount > 0 || p.crashCount > 0) {
                status = "エラー " + p.errorCount + "件 / クラッシュ " + p.crashCount + "件"
                        + (p.lastError.isEmpty() ? "" : "\n" + p.lastError);
            }
            if (status == null) {
                h.status.setVisibility(View.GONE);
            } else {
                h.status.setVisibility(View.VISIBLE);
                h.status.setText(status);
            }
            h.toggle.setOnCheckedChangeListener(null);
            h.toggle.setChecked(p.enabled);
            h.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    manager.setEnabled(p.id, isChecked);
                    refresh();
                }
            });
            h.menu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(PluginManagerActivity.this, v);
                popup.getMenu().add(0, 1, 0, "詳細");
                popup.getMenu().add(0, 2, 1, "ログ");
                popup.getMenu().add(0, 3, 2, "削除");
                popup.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == 1) {
                        showDetails(p);
                    } else if (id == 2) {
                        showLogDialog(p.id);
                    } else if (id == 3) {
                        confirmDelete(p);
                    }
                    return true;
                });
                popup.show();
            });
            return row;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView title;
        TextView meta;
        TextView description;
        TextView status;
        SwitchCompat toggle;
        TextView menu;
    }
}
