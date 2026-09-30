package com.coara.browser;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.webkit.CookieManager;
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

import com.coara.browser.util.BrowserConstants;
import com.coara.browser.util.UiThread;
import com.coara.browser.util.BasicAuthManager;
import com.coara.browser.util.DownloadFallbackManager;
import com.coara.browser.util.DownloadSupport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadHistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvEmpty;
    private DownloadAdapter adapter;
    private List<DownloadItem> downloadItems;
    private SharedPreferences pref;

    private DownloadManager downloadManager;
    private final Handler updateHandler = UiThread.mainHandler();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    
    
    
    private final AtomicInteger pollGeneration = new AtomicInteger(0);

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            final int myGeneration = pollGeneration.get();
            final List<DownloadItem> itemsSnapshot = downloadItems;
            if (itemsSnapshot == null || adapter == null) {
                updateHandler.postDelayed(this, 1000);
                return;
            }

            
            final List<Long> pendingIds = new ArrayList<>();
            for (int i = 0, size = itemsSnapshot.size(); i < size; i++) {
                DownloadItem item = itemsSnapshot.get(i);
                if (item.status != DownloadManager.STATUS_SUCCESSFUL
                        && item.status != DownloadManager.STATUS_FAILED) {
                    pendingIds.add(item.downloadId);
                }
            }
            if (pendingIds.isEmpty()) {
                updateHandler.postDelayed(this, 1000);
                return;
            }

            
            
            executor.execute(() -> {
                final Map<Long, DownloadItem> updates = new HashMap<>();
                for (Long id : pendingIds) {
                    if (pollGeneration.get() != myGeneration) {
                        return; 
                    }
                    DownloadItem updated = getDownloadItem(id);
                    if (updated != null) {
                        updates.put(id, updated);
                    }
                }

                updateHandler.post(() -> {
                    if (pollGeneration.get() != myGeneration || isFinishing()) {
                        return;
                    }
                    boolean needUpdate = false;
                    List<DownloadItem> current = downloadItems;
                    if (current != null && adapter != null) {
                        for (int i = 0, size = current.size(); i < size; i++) {
                            DownloadItem currentItem = current.get(i);
                            DownloadItem updated = updates.get(currentItem.downloadId);
                            if (updated == null) continue;
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
                        if (needUpdate) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                    updateHandler.postDelayed(updateRunnable, 1000);
                });
            });
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
        pref = getSharedPreferences(BrowserConstants.PREF_NAME, Context.MODE_PRIVATE);

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
        pollGeneration.incrementAndGet();
        updateHandler.post(updateRunnable);
    }

    @Override
    protected void onPause() {
        pollGeneration.incrementAndGet();
        updateHandler.removeCallbacks(updateRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pollGeneration.incrementAndGet();
        updateHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadDownloadHistory() {
        executor.execute(() -> {
            List<DownloadItem> items = new ArrayList<>();
            String jsonStr = pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]");
            try {
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0, len = array.length(); i < len; i++) {
                    JSONObject obj = array.getJSONObject(i);
                    long downloadId = obj.getLong("id");
                    String storedFileName = obj.optString("fileName", "");
                    String filePath = obj.optString("filePath", "");
                    String storedLocalUri = obj.optString("localUri", "");
                    DownloadItem item = getDownloadItem(downloadId);
                    if (item == null) {
                        String fileName = !storedFileName.isEmpty() ? storedFileName : fileNameFromPath(filePath, storedLocalUri);
                        String localUri = storedLocalUri;
                        if (localUri.isEmpty() && !filePath.isEmpty()) {
                            localUri = Uri.fromFile(new File(filePath)).toString();
                        }
                        if (obj.has("manualStatus")) {
                            int status = obj.optInt("manualStatus", DownloadManager.STATUS_FAILED);
                            long downloadedSize = obj.optLong("manualDownloaded", 0);
                            long totalSize = obj.optLong("manualTotal", 0);
                            item = new DownloadItem(downloadId, fileName, "", status, downloadedSize,
                                    totalSize, localUri, "");
                        } else {
                            boolean exists = itemExists(filePath, localUri);
                            long size = getItemSize(filePath, localUri);
                            int status = exists ? DownloadManager.STATUS_SUCCESSFUL : DownloadManager.STATUS_FAILED;
                            item = new DownloadItem(downloadId, fileName, "", status, 0, size, localUri, "");
                        }
                    } else {
                        if (item.title == null || item.title.isEmpty() || "Unknown".equalsIgnoreCase(item.title)) {
                            if (!storedFileName.isEmpty()) {
                                item.title = storedFileName;
                            } else if (!filePath.isEmpty()) {
                                item.title = new File(filePath).getName();
                            } else if (!storedLocalUri.isEmpty()) {
                                item.title = fileNameFromPath("", storedLocalUri);
                            }
                        }
                        if (DownloadSupport.isBlank(item.localUri)) {
                            item.localUri = storedLocalUri;
                        }
                    }
                    item.filePath = filePath;
                    item.userAgent = obj.optString("userAgent", "");
                    item.referer = obj.optString("referer", "");
                    item.basicAuthEnabled = obj.optBoolean("basicAuthEnabled", false);
                    if (DownloadSupport.isBlank(item.localUri) && !DownloadSupport.isBlank(storedLocalUri)) {
                        item.localUri = storedLocalUri;
                    }
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

    private String fileNameFromPath(String filePath, String localUri) {
        if (!DownloadSupport.isBlank(filePath)) {
            String name = new File(filePath).getName();
            if (!DownloadSupport.isBlank(name)) return name;
        }
        if (!DownloadSupport.isBlank(localUri)) {
            try {
                Uri uri = Uri.parse(localUri);
                String name = uri.getLastPathSegment();
                if (!DownloadSupport.isBlank(name)) return name;
            } catch (Exception ignored) {
            }
        }
        return "Unknown";
    }

    private boolean itemExists(DownloadItem item) {
        return item != null && itemExists(item.filePath, item.localUri);
    }

    private boolean itemExists(String filePath, String localUri) {
        if (!DownloadSupport.isBlank(localUri)) {
            try {
                Uri uri = Uri.parse(localUri);
                if ("content".equalsIgnoreCase(uri.getScheme())) {
                    try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                        if (fd != null) return true;
                    }
                } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                    String path = uri.getPath();
                    if (!DownloadSupport.isBlank(path) && new File(path).exists()) return true;
                }
            } catch (Exception ignored) {
            }
        }
        return !DownloadSupport.isBlank(filePath) && new File(filePath).exists();
    }

    private long getItemSize(String filePath, String localUri) {
        if (!DownloadSupport.isBlank(localUri)) {
            try {
                Uri uri = Uri.parse(localUri);
                if ("content".equalsIgnoreCase(uri.getScheme())) {
                    try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                        if (fd != null && fd.getLength() > 0) return fd.getLength();
                    }
                } else if ("file".equalsIgnoreCase(uri.getScheme()) && !DownloadSupport.isBlank(uri.getPath())) {
                    File file = new File(uri.getPath());
                    if (file.exists()) return file.length();
                }
            } catch (Exception ignored) {
            }
        }
        File file = DownloadSupport.isBlank(filePath) ? null : new File(filePath);
        return file != null && file.exists() ? file.length() : 0L;
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
                return new DownloadItem(downloadId, title, description, status, downloadedSize,
                        totalSize, localUri, downloadUrl);
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


    private String getMimeTypeFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        String lower = filePath.toLowerCase(Locale.ROOT);

        int queryPos = lower.indexOf('?');
        if (queryPos != -1) lower = lower.substring(0, queryPos);

        int dotPos = lower.lastIndexOf('.');
        if (dotPos < 0 || dotPos == lower.length() - 1) return null;

        String ext = lower.substring(dotPos + 1);

        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null && !mime.isEmpty()) return mime;

        switch (ext) {
            case "apk":   return "application/vnd.android.package-archive";
            case "pdf":   return "application/pdf";
            case "zip":   return "application/zip";
            case "rar":   return "application/x-rar-compressed";
            case "7z":    return "application/x-7z-compressed";
            case "tar":   return "application/x-tar";
            case "gz":    return "application/gzip";
            case "mp4":
            case "m4v":   return "video/mp4";
            case "mkv":   return "video/x-matroska";
            case "avi":   return "video/x-msvideo";
            case "mov":   return "video/quicktime";
            case "webm":  return "video/webm";
            case "mp3":   return "audio/mpeg";
            case "aac":   return "audio/aac";
            case "ogg":   return "audio/ogg";
            case "flac":  return "audio/flac";
            case "wav":   return "audio/wav";
            case "m4a":   return "audio/mp4";
            case "jpg":
            case "jpeg":  return "image/jpeg";
            case "png":   return "image/png";
            case "gif":   return "image/gif";
            case "webp":  return "image/webp";
            case "bmp":   return "image/bmp";
            case "svg":   return "image/svg+xml";
            case "ico":   return "image/x-icon";
            case "txt":   return "text/plain";
            case "html":
            case "htm":   return "text/html";
            case "csv":   return "text/csv";
            case "json":  return "application/json";
            case "xml":   return "application/xml";
            case "doc":   return "application/msword";
            case "docx":  return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":   return "application/vnd.ms-excel";
            case "xlsx":  return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":   return "application/vnd.ms-powerpoint";
            case "pptx":  return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "epub":  return "application/epub+zip";
            default:      return null;
        }
    }


    private void openFileWithApp(Context ctx, DownloadItem item) {
        if (item == null || !itemExists(item)) {
            Toast.makeText(ctx, "ファイルが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = !DownloadSupport.isBlank(item.title) ? item.title : fileNameFromPath(item.filePath, item.localUri);
        String mimeType = getMimeTypeFromPath(fileName);
        Uri sourceUri = null;
        try {
            if (!DownloadSupport.isBlank(item.localUri)) {
                sourceUri = Uri.parse(item.localUri);
            }
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (sourceUri != null && "content".equalsIgnoreCase(sourceUri.getScheme())) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (sourceUri != null && "file".equalsIgnoreCase(sourceUri.getScheme())) {
                File sourceFile = new File(sourceUri.getPath());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    sourceUri = FileProvider.getUriForFile(
                            ctx, ctx.getPackageName() + ".fileprovider", sourceFile);
                }
            } else if (!DownloadSupport.isBlank(item.filePath)) {
                File targetFile = new File(item.filePath);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    sourceUri = FileProvider.getUriForFile(
                            ctx, ctx.getPackageName() + ".fileprovider", targetFile);
                } else {
                    sourceUri = Uri.fromFile(targetFile);
                }
            }
            if (sourceUri == null) {
                throw new IllegalStateException("invalid file URI");
            }
            intent.setDataAndType(sourceUri, mimeType == null ? "*/*" : mimeType);
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "開けるアプリがありません", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "ファイルを開けません", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeHistoryRecord(long downloadId) {
        try {
            String jsonStr = pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]");
            JSONArray array = new JSONArray(jsonStr);
            JSONArray updated = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.optLong("id", -1L) != downloadId) {
                    updated.put(obj);
                }
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void replaceHistoryRecord(long oldId, long newId, String title, String path) {
        try {
            String jsonStr = pref.getString(BrowserConstants.KEY_DOWNLOAD_HISTORY, "[]");
            JSONArray array = new JSONArray(jsonStr);
            JSONArray updated = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.optLong("id", -1L) == oldId) {
                    JSONObject next = new JSONObject(obj.toString());
                    next.put("id", newId);
                    next.put("fileName", title != null ? title : "");
                    next.put("filePath", path != null ? path : "");
                    next.remove("localUri");
                    next.remove("manualStatus");
                    next.remove("manualDownloaded");
                    next.remove("manualTotal");
                    updated.put(next);
                } else {
                    updated.put(obj);
                }
            }
            pref.edit().putString(BrowserConstants.KEY_DOWNLOAD_HISTORY, updated.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void clearDownloadHistory() {
        pref.edit().remove(BrowserConstants.KEY_DOWNLOAD_HISTORY).apply();
        if (downloadItems != null) downloadItems.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
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
        public String userAgent;
        public String referer;
        public boolean basicAuthEnabled;

        public DownloadItem(long downloadId, String title, String description,
                int status, long downloadedSize, long totalSize,
                String localUri, String downloadUrl) {
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
            this.userAgent = "";
            this.referer = "";
            this.basicAuthEnabled = false;
        }

        public int getProgress() {
            return totalSize > 0 ? (int) ((downloadedSize * 100) / totalSize) : 0;
        }
    }

    public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

        private final List<DownloadItem> items;
        private final Context context;

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
            boolean fileExists = itemExists(item);
            if (item.title == null || item.title.isEmpty()) {
                item.title = "ダウンロード " + item.downloadId;
            }

            if (!fileExists) {
                holder.fileTitle.setText(item.title + " [削除済]");
                holder.fileTitle.setPaintFlags(
                        holder.fileTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                holder.fileTitle.setText(item.title);
                holder.fileTitle.setPaintFlags(
                        holder.fileTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            }

            String statusText;
            boolean showProgress = false;
            boolean showOpenButton = false;
            switch (item.status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    statusText = "完了 (" + formatSize(item.totalSize) + ")";
                    showOpenButton = fileExists;
                    break;
                case DownloadManager.STATUS_FAILED:
                    statusText = "失敗";
                    break;
                case DownloadManager.STATUS_RUNNING:
                    statusText = "ダウンロード中 (" + formatSize(item.downloadedSize)
                            + " / " + formatSize(item.totalSize)
                            + ", " + item.getProgress() + "%)";
                    showProgress = true;
                    break;
                case DownloadManager.STATUS_PAUSED:
                    statusText = "一時停止中 (" + formatSize(item.downloadedSize)
                            + " / " + formatSize(item.totalSize)
                            + ", " + item.getProgress() + "%)";
                    showProgress = true;
                    break;
                case DownloadManager.STATUS_PENDING:
                    statusText = "待機中";
                    break;
                default:
                    statusText = "不明";
            }
            if (item.isPaused) {
                statusText = "一時停止中 (" + formatSize(item.downloadedSize)
                        + " / " + formatSize(item.totalSize)
                        + ", " + item.getProgress() + "%)";
                showProgress = true;
            }
            holder.fileStatus.setText(statusText);
            holder.progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            if (showProgress) holder.progressBar.setProgress(item.getProgress());

            if (showOpenButton) {
                holder.btnOpenFile.setVisibility(View.VISIBLE);
                holder.btnOpenFile.setOnClickListener(v -> openFileWithApp(context, item));
            } else {
                holder.btnOpenFile.setVisibility(View.GONE);
            }

            if (item.title != null && item.title.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                holder.itemView.setOnClickListener(v -> openFileWithApp(context, item));
            } else {
                holder.itemView.setOnClickListener(null);
            }

            holder.itemView.setOnLongClickListener(v -> {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION || adapterPosition >= items.size()) {
                    return true;
                }
                DownloadItem currentItem = items.get(adapterPosition);
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                if (!fileExists) {
                    builder.setTitle("操作を選択")
                           .setItems(new String[]{"履歴から消去"}, (dialog, which) -> {
                               if (which == 0) {
                                   int currentPosition = items.indexOf(currentItem);
                                   if (currentPosition >= 0) {
                                       items.remove(currentPosition);
                                       notifyItemRemoved(currentPosition);
                                       removeHistoryRecord(currentItem.downloadId);
                                   }
                                   Toast.makeText(context, "履歴から消去しました",
                                           Toast.LENGTH_SHORT).show();
                               }
                           })
                           .setNegativeButton("閉じる", null)
                           .show();
                } else {
                    if (currentItem.status == DownloadManager.STATUS_SUCCESSFUL) {
                        builder.setTitle("操作を選択")
                               .setItems(new String[]{"ファイル削除"}, (dialog, which) -> {
                                   if (which == 0) {
                                       if (DownloadSupport.deleteLocalUri(context, currentItem.localUri)
                                               || (!DownloadSupport.isBlank(currentItem.filePath) && new File(currentItem.filePath).delete())) {
                                           Toast.makeText(context, "ファイルを削除しました",
                                                   Toast.LENGTH_SHORT).show();
                                       } else {
                                           Toast.makeText(context, "ファイルの削除に失敗しました",
                                                   Toast.LENGTH_SHORT).show();
                                       }
                                       int currentPosition = items.indexOf(currentItem);
                                       if (currentPosition >= 0) {
                                           items.remove(currentPosition);
                                           notifyItemRemoved(currentPosition);
                                           removeHistoryRecord(currentItem.downloadId);
                                       }
                                   }
                               })
                               .setNegativeButton("閉じる", null)
                               .show();
                    } else {
                        if (!currentItem.isPaused) {
                            builder.setTitle("操作を選択")
                                   .setItems(new String[]{"キャンセル", "停止"}, (dialog, which) -> {
                                       if (which == 0) {
                                           try {
                                               downloadManager.remove(currentItem.downloadId);
                                           } catch (Exception ignored) {
                                           }
                                           Toast.makeText(context,
                                                   "ダウンロードをキャンセルしました",
                                                   Toast.LENGTH_SHORT).show();
                                           int currentPosition = items.indexOf(currentItem);
                                           if (currentPosition >= 0) {
                                               items.remove(currentPosition);
                                               notifyItemRemoved(currentPosition);
                                               removeHistoryRecord(currentItem.downloadId);
                                           }
                                       } else if (which == 1) {
                                           downloadManager.remove(currentItem.downloadId);
                                           currentItem.isPaused = true;
                                           Toast.makeText(context,
                                                   "ダウンロードを一時停止しました",
                                                   Toast.LENGTH_SHORT).show();
                                           int currentPosition = items.indexOf(currentItem);
                                           if (currentPosition >= 0) {
                                               notifyItemChanged(currentPosition);
                                           }
                                       }
                                   })
                                   .setNegativeButton("閉じる", null)
                                   .show();
                        } else {
                            builder.setTitle("操作を選択")
                                   .setItems(new String[]{"キャンセル", "再開"}, (dialog, which) -> {
                                       if (which == 0) {
                                           try {
                                               downloadManager.remove(currentItem.downloadId);
                                           } catch (Exception ignored) {
                                           }
                                           Toast.makeText(context,
                                                   "ダウンロードをキャンセルしました",
                                                   Toast.LENGTH_SHORT).show();
                                           int currentPosition = items.indexOf(currentItem);
                                           if (currentPosition >= 0) {
                                               items.remove(currentPosition);
                                               notifyItemRemoved(currentPosition);
                                               removeHistoryRecord(currentItem.downloadId);
                                           }
                                       } else if (which == 1) {
                                           try {
                                               if (DownloadSupport.isBlank(currentItem.downloadUrl)) {
                                                   throw new IllegalStateException("download url missing");
                                               }
                                               DownloadSupport.deleteLocalUri(context, currentItem.localUri);
                                               DownloadManager.Request request =
                                                       new DownloadManager.Request(Uri.parse(currentItem.downloadUrl));
                                               request.setTitle(currentItem.title);
                                               request.setDescription(currentItem.description != null
                                                       ? currentItem.description : "Downloading file...");
                                               DownloadSupport.addHeaderIfSafe(request, "User-Agent", currentItem.userAgent);
                                               DownloadSupport.addHeaderIfSafe(request, "Referer", currentItem.referer);
                                               DownloadSupport.addHeaderIfSafe(request, "Cookie", CookieManager.getInstance().getCookie(currentItem.downloadUrl));
                                               DownloadSupport.addHeaderIfSafe(request, "Authorization",
                                                       BasicAuthManager.getAuthorizationHeaderForUrl(currentItem.downloadUrl, currentItem.basicAuthEnabled));
                                               request.setNotificationVisibility(
                                                       DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                               request.setAllowedOverMetered(true);
                                               request.setAllowedOverRoaming(true);
                                               String resumeName = DownloadSupport.resolveUniqueDownloadFileName(DownloadHistoryActivity.this, currentItem.title);
                                               request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, resumeName);
                                               currentItem.title = resumeName;
                                               long oldId = currentItem.downloadId;
                                               long newId = downloadManager.enqueue(request);
                                               currentItem.downloadId = newId;
                                               currentItem.isPaused = false;
                                               replaceHistoryRecord(oldId, newId, currentItem.title, currentItem.filePath);
                                               DownloadFallbackManager.register(newId, new DownloadFallbackManager.Spec(
                                                       DownloadHistoryActivity.this, null, currentItem.downloadUrl, currentItem.userAgent, null,
                                                       getMimeTypeFromPath(currentItem.title), currentItem.totalSize, currentItem.referer, currentItem.title,
                                                       getMimeTypeFromPath(currentItem.title), currentItem.basicAuthEnabled));
                                               DownloadHistoryManager.monitorDownloadProgress(
                                                       DownloadHistoryActivity.this, newId, downloadManager);
                                               Toast.makeText(context,
                                                       "ダウンロードを再開しました",
                                                       Toast.LENGTH_SHORT).show();
                                               int currentPosition = items.indexOf(currentItem);
                                               if (currentPosition >= 0) {
                                                   notifyItemChanged(currentPosition);
                                               }
                                           } catch (Exception e) {
                                               Toast.makeText(context, "ダウンロードを再開できませんでした", Toast.LENGTH_SHORT).show();
                                           }
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
        public int getItemCount() { return items.size(); }

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
            final String[] units = {"B", "KB", "MB", "GB", "TB"};
            int dg = (int) (Math.log10(size) / Math.log10(1024));
            return String.format(Locale.ROOT, "%.1f %s", size / Math.pow(1024, dg), units[dg]);
        }
    }
}
