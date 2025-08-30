package com.coara.browser;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadHistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvEmpty;
    private DownloadAdapter adapter;
    private List<DownloadItem> downloadItems;
    private SharedPreferences pref;
    private static final String PREF_NAME = "AdvancedBrowserPrefs";
    private static final String KEY_DOWNLOAD_HISTORY = "download_history";

    private DownloadManager downloadManager;
    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            boolean needUpdate = false;
            if (downloadItems != null && adapter != null) {
                for (int i = 0, size = downloadItems.size(); i < size; i++) {
                    DownloadItem currentItem = downloadItems.get(i);
                    DownloadItem updated = getDownloadItem(currentItem.downloadId);
                    if (updated != null) {
                        if (currentItem.status != updated.status ||
                                currentItem.downloadedSize != updated.downloadedSize ||
                                currentItem.totalSize != updated.totalSize) {
                            currentItem.status = updated.status;
                            currentItem.downloadedSize = updated.downloadedSize;
                            currentItem.totalSize = updated.totalSize;
                            needUpdate = true;
                        }
                        if (currentItem.title == null || currentItem.title.isEmpty()) {
                            currentItem.title = updated.title;
                        }
                        currentItem.localUri = updated.localUri;
                    }
                }
                if (needUpdate) {
                    adapter.notifyDataSetChanged();
                }
            }
            updateHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_history);

        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        tvEmpty = findViewById(R.id.tvEmpty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        pref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        if (getIntent().getBooleanExtra("clear_history", false)) {
            clearDownloadHistory();
        }
        loadDownloadHistory();

        swipeRefresh.setOnRefreshListener(() -> {
            loadDownloadHistory();
            swipeRefresh.setRefreshing(false);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHandler.post(updateRunnable);
    }

    @Override
    protected void onPause() {
        updateHandler.removeCallbacks(updateRunnable);
        super.onPause();
    }

    private void loadDownloadHistory() {
        executor.execute(() -> {
            List<DownloadItem> items = new ArrayList<>();
            String jsonStr = pref.getString(KEY_DOWNLOAD_HISTORY, "[]");
            try {
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0, len = array.length(); i < len; i++) {
                    JSONObject obj = array.getJSONObject(i);
                    long downloadId = obj.getLong("id");
                    String storedFileName = obj.optString("fileName", "");
                    String filePath = obj.optString("filePath", "");
                    DownloadItem item = getDownloadItem(downloadId);
                    if (item == null) {
                        File file = new File(filePath);
                        int status = file.exists() ? DownloadManager.STATUS_SUCCESSFUL : DownloadManager.STATUS_FAILED;
                        String fileName = !storedFileName.isEmpty() ? storedFileName : file.getName();
                        item = new DownloadItem(downloadId, fileName, "", status, 0, file.exists() ? file.length() : 0,
                                "file://" + filePath, "");
                    } else {
                        if (item.title == null || item.title.isEmpty()) {
                            if (!storedFileName.isEmpty()) {
                                item.title = storedFileName;
                            } else if (!filePath.isEmpty()) {
                                File file = new File(filePath);
                                item.title = file.getName();
                            }
                        }
                        item.localUri = "file://" + filePath;
                    }
                    item.filePath = filePath;
                    items.add(item);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            downloadItems = items;
            runOnUiThread(() -> {
                if (downloadItems.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                }
                adapter = new DownloadAdapter(DownloadHistoryActivity.this, downloadItems);
                recyclerView.setAdapter(adapter);
            });
        });
    }

    private DownloadItem getDownloadItem(long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                String title = getRobustFileName(cursor);
                String description = safeGetString(cursor, DownloadManager.COLUMN_DESCRIPTION);
                int status = safeGetInt(cursor, DownloadManager.COLUMN_STATUS);
                long totalSize = safeGetLong(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                long downloadedSize = safeGetLong(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                String localUri = safeGetString(cursor, DownloadManager.COLUMN_LOCAL_URI);
                String downloadUrl = safeGetString(cursor, DownloadManager.COLUMN_URI);
                return new DownloadItem(downloadId, title, description, status, downloadedSize, totalSize, localUri, downloadUrl);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getRobustFileName(Cursor cursor) {
        String title = safeGetString(cursor, DownloadManager.COLUMN_TITLE);
        if (title == null || title.isEmpty()) {
            String localUri = safeGetString(cursor, DownloadManager.COLUMN_LOCAL_URI);
            if (localUri != null && !localUri.isEmpty()) {
                try {
                    Uri uri = Uri.parse(localUri);
                    String path = uri.getPath();
                    if (path != null && !path.isEmpty()) {
                        title = new File(path).getName();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        if (title == null || title.isEmpty()) {
            String downloadUrl = safeGetString(cursor, DownloadManager.COLUMN_URI);
            title = URLUtil.guessFileName(downloadUrl, null, null);
        }
        return (title == null || title.isEmpty()) ? "Unknown" : title;
    }

    private String safeGetString(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getString(index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private int safeGetInt(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getInt(index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private long safeGetLong(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndexOrThrow(columnName);
            return cursor.getLong(index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void clearDownloadHistory() {
        pref.edit().remove(KEY_DOWNLOAD_HISTORY).apply();
        Toast.makeText(this, "ダウンロード履歴を全消去しました", Toast.LENGTH_SHORT).show();
    }

    public static class DownloadItem {
        public long downloadId;
        public String title;
        public String description;
        public int status;
        public long downloadedSize;
        public long totalSize;
        public String localUri;
        public String downloadUrl;
        public boolean isPaused;
        public String filePath;

        public DownloadItem(long downloadId, String title, String description, int status, long downloadedSize, long totalSize, String localUri, String downloadUrl) {
            this.downloadId = downloadId;
            this.title = title;
            this.description = description;
            this.status = status;
            this.downloadedSize = downloadedSize;
            this.totalSize = totalSize;
            this.localUri = localUri;
            this.downloadUrl = downloadUrl;
            this.isPaused = false;
            this.filePath = "";
        }

        public int getProgress() {
            return totalSize > 0 ? (int) ((downloadedSize * 100) / totalSize) : 0;
        }
    }

    public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

        private List<DownloadItem> items;
        private Context context;

        public DownloadAdapter(Context context, List<DownloadItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_download, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            DownloadItem item = items.get(position);
            File file = new File(item.filePath);
            if (item.title == null || item.title.isEmpty()) {
                item.title = "ダウンロード " + item.downloadId;
            }
            if (!file.exists()) {
                holder.fileTitle.setText(item.title + " [削除済]");
                holder.fileTitle.setPaintFlags(holder.fileTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                holder.fileTitle.setText(item.title);
                holder.fileTitle.setPaintFlags(holder.fileTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            }
            String statusText;
            boolean showProgress = false;
            boolean showOpenButton = false;
            switch (item.status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    statusText = "完了 (" + formatSize(item.totalSize) + ")";
                    showOpenButton = true;
                    break;
                case DownloadManager.STATUS_FAILED:
                    statusText = "失敗";
                    break;
                case DownloadManager.STATUS_RUNNING:
                    statusText = "ダウンロード中 (" + formatSize(item.downloadedSize) + " / " + formatSize(item.totalSize) + ", " + item.getProgress() + "%)";
                    showProgress = true;
                    break;
                case DownloadManager.STATUS_PAUSED:
                    statusText = "一時停止中 (" + formatSize(item.downloadedSize) + " / " + formatSize(item.totalSize) + ", " + item.getProgress() + "%)";
                    showProgress = true;
                    break;
                case DownloadManager.STATUS_PENDING:
                    statusText = "待機中";
                    break;
                default:
                    statusText = "不明";
            }
            if (item.isPaused) {
                statusText = "一時停止中 (" + formatSize(item.downloadedSize) + " / " + formatSize(item.totalSize) + ", " + item.getProgress() + "%)";
                showProgress = true;
            }
            holder.fileStatus.setText(statusText);
            if (showProgress) {
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setProgress(item.getProgress());
            } else {
                holder.progressBar.setVisibility(View.GONE);
            }
            if (showOpenButton) {
                holder.btnOpenFile.setVisibility(View.VISIBLE);
                holder.btnOpenFile.setOnClickListener(v -> {
                    try {
                        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(context, "ダウンロード一覧を開けません", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.btnOpenFile.setVisibility(View.GONE);
            }


            if (item.filePath != null && item.filePath.toLowerCase().endsWith(".apk")) {
                holder.itemView.setOnClickListener(v -> {
                    File apkFile = new File(item.filePath);
                    if (apkFile.exists()) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
                                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                            } else {
                                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                            }
                            context.startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(context, "インストールできません: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(context, "ファイルが存在しません", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.itemView.setOnClickListener(null);
            }

            holder.itemView.setOnLongClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                if (!file.exists()) {
                    builder.setTitle("操作を選択")
                           .setItems(new String[]{"履歴から消去"}, (dialog, which) -> {
                               if (which == 0) {
                                   items.remove(position);
                                   notifyItemRemoved(position);
                                   Toast.makeText(context, "履歴から消去しました", Toast.LENGTH_SHORT).show();
                               }
                           })
                           .setNegativeButton("閉じる", null)
                           .show();
                } else {
                    if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                        builder.setTitle("操作を選択")
                               .setItems(new String[]{"ファイル削除"}, (dialog, which) -> {
                                   if (which == 0) {
                                       File delFile = new File(item.filePath);
                                       if (delFile.exists() && delFile.delete()) {
                                           Toast.makeText(context, "ファイルを削除しました", Toast.LENGTH_SHORT).show();
                                       } else {
                                           Toast.makeText(context, "ファイルの削除に失敗しました", Toast.LENGTH_SHORT).show();
                                       }
                                       items.remove(position);
                                       notifyItemRemoved(position);
                                   }
                               })
                               .setNegativeButton("閉じる", null)
                               .show();
                    } else {
                        if (!item.isPaused) {
                            builder.setTitle("操作を選択")
                                   .setItems(new String[]{"キャンセル", "停止"}, (dialog, which) -> {
                                       if (which == 0) {
                                           downloadManager.remove(item.downloadId);
                                           Toast.makeText(context, "ダウンロードをキャンセルしました", Toast.LENGTH_SHORT).show();
                                           items.remove(position);
                                           notifyItemRemoved(position);
                                       } else if (which == 1) {
                                           downloadManager.remove(item.downloadId);
                                           item.isPaused = true;
                                           Toast.makeText(context, "ダウンロードを一時停止しました", Toast.LENGTH_SHORT).show();
                                           notifyItemChanged(position);
                                       }
                                   })
                                   .setNegativeButton("閉じる", null)
                                   .show();
                        } else {
                            builder.setTitle("操作を選択")
                                   .setItems(new String[]{"キャンセル", "再開"}, (dialog, which) -> {
                                       if (which == 0) {
                                           Toast.makeText(context, "ダウンロードをキャンセルしました", Toast.LENGTH_SHORT).show();
                                           items.remove(position);
                                           notifyItemRemoved(position);
                                       } else if (which == 1) {
                                           DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.downloadUrl));
                                           request.setTitle(item.title);
                                           request.setDescription(item.description != null ? item.description : "Downloading file...");
                                           request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                           long newDownloadId = downloadManager.enqueue(request);
                                           item.downloadId = newDownloadId;
                                           item.isPaused = false;
                                           Toast.makeText(context, "ダウンロードを再開しました", Toast.LENGTH_SHORT).show();
                                           notifyItemChanged(position);
                                       }
                                   })
                                   .setNegativeButton("閉じる", null)
                                   .show();
                        }
                    }
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            ImageView fileIcon;
            TextView fileTitle;
            TextView fileStatus;
            ProgressBar progressBar;
            Button btnOpenFile;

            public ViewHolder(View itemView) {
                super(itemView);
                fileIcon = itemView.findViewById(R.id.fileIcon);
                fileTitle = itemView.findViewById(R.id.fileTitle);
                fileStatus = itemView.findViewById(R.id.fileStatus);
                progressBar = itemView.findViewById(R.id.progressBar);
                btnOpenFile = itemView.findViewById(R.id.btnOpenFile);
            }
        }

        private String formatSize(long size) {
            if (size <= 0) return "0 B";
            final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
            int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
            return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }
}
