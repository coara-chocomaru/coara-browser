package com.coara.browser;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class htmlview extends AppCompatActivity {

    private static final int TAG_COLOR = 0xFF0000FF;       // 青
    private static final int ATTRIBUTE_COLOR = 0xFF008000; // 緑
    private static final int VALUE_COLOR = 0xFFB22222;     // 赤

    private static final int LARGE_TEXT_THRESHOLD = 700;
    private static final int REQUEST_PERMISSION_WRITE = 100;
    private static final int REQUEST_CODE_PICK_HTML = 101;

    private EditText urlInput;
    private Button loadButton, loadFromStorageButton, editButton, saveButton, searchButton;
    private EditText htmlEditText;
    private FloatingActionButton revertFab;
    private RelativeLayout searchOverlay;
    private EditText searchQueryEditText;
    private TextView searchResultCountTextView;
    private Button searchNextButton, searchPrevButton, closeSearchButton;

    private String originalHtml = "";
    private final Stack<String> editHistory = new Stack<>();
    private boolean isEditing = false;
    private volatile boolean isUpdating = false;
    private volatile boolean isLoading = false;
    private long lastUndoTimestamp = 0;
    private static final long UNDO_THRESHOLD = 1000;

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)=\\\"([^\\\"]*)\\\"");
    private final ArrayList<Integer> searchMatchPositions = new ArrayList<>();
    private int currentSearchIndex = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Runnable highlightRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.htmlview);

        urlInput = findViewById(R.id.urlInput);
        loadButton = findViewById(R.id.loadButton);
        loadFromStorageButton = findViewById(R.id.loadFromStorageButton);
        editButton = findViewById(R.id.editButton);
        saveButton = findViewById(R.id.saveButton);
        htmlEditText = findViewById(R.id.htmlEditText);
        revertFab = findViewById(R.id.revertFab);
        searchButton = findViewById(R.id.searchButton);
        searchOverlay = findViewById(R.id.searchOverlay);
        searchQueryEditText = findViewById(R.id.searchQueryEditText);
        searchResultCountTextView = findViewById(R.id.searchResultCountTextView);
        searchNextButton = findViewById(R.id.searchNextButton);
        searchPrevButton = findViewById(R.id.searchPrevButton);
        closeSearchButton = findViewById(R.id.closeSearchButton);

        htmlEditText.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        htmlEditText.setTextIsSelectable(true);

        loadButton.setOnClickListener(v -> {
            String urlStr = urlInput.getText().toString().trim();
            if ((urlStr.startsWith("http://") || urlStr.startsWith("https://")) && !isLoading) {
                fetchHtml(urlStr);
            } else if (isLoading) {
                Toast.makeText(htmlview.this, "読み込み中です。", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(htmlview.this, "正しいURLを入力してください", Toast.LENGTH_SHORT).show();
            }
        });

        loadFromStorageButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/html");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "HTMLファイルを選択"), REQUEST_CODE_PICK_HTML);
        });

        editButton.setOnClickListener(v -> {
            if (!isEditing) {
                editHistory.clear();
                editHistory.push(htmlEditText.getText().toString());
                lastUndoTimestamp = System.currentTimeMillis();
                htmlEditText.setKeyListener(new EditText(htmlview.this).getKeyListener());
                htmlEditText.setFocusableInTouchMode(true);
                isEditing = true;
                Toast.makeText(htmlview.this, "編集モードに入りました", Toast.LENGTH_SHORT).show();
            }
        });

        htmlEditText.addTextChangedListener(new TextWatcher() {
            private String beforeChange;
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!isUpdating && isEditing) {
                    beforeChange = s.toString();
                }
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (highlightRunnable != null) {
                    uiHandler.removeCallbacks(highlightRunnable);
                }
            }
            @Override
            public void afterTextChanged(final Editable s) {
                if (!isUpdating && isEditing) {
                    final String newText = s.toString();
                    long now = System.currentTimeMillis();
                    if (now - lastUndoTimestamp > UNDO_THRESHOLD) {
                        editHistory.push(beforeChange);
                        lastUndoTimestamp = now;
                    }
                    highlightRunnable = () -> {
                        if (!isUpdating) {
                            isUpdating = true;
                            final String currentText = newText;
                            executor.execute(() -> {
                                final int[][] spans = getHighlightSpans(currentText);
                                uiHandler.post(() -> {
                                    applyHighlight(htmlEditText.getText(), spans);
                                    isUpdating = false;
                                });
                            });
                        }
                    };
                    uiHandler.postDelayed(highlightRunnable, 150);
                }
            }
        });

        revertFab.setOnClickListener(v -> {
            if (isEditing && !isUpdating && !editHistory.isEmpty()) {
                final String previousText = editHistory.pop();
                isUpdating = true;
                final Editable editable = htmlEditText.getText();
                final int curPos = htmlEditText.getSelectionStart();
                editable.replace(0, editable.length(), previousText);
                executor.execute(() -> {
                    final int[][] spans = getHighlightSpans(previousText);
                    uiHandler.post(() -> {
                        applyHighlight(htmlEditText.getText(), spans);
                        int pos = Math.min(previousText.length(), curPos);
                        htmlEditText.setSelection(pos);
                        isUpdating = false;
                        Toast.makeText(htmlview.this, "変更を元に戻しました", Toast.LENGTH_SHORT).show();
                    });
                });
            } else if (isEditing && !isUpdating) {
                Toast.makeText(htmlview.this, "これ以上前はありません", Toast.LENGTH_SHORT).show();
            }
        });

        saveButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(htmlview.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(htmlview.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_WRITE);
            } else {
                saveHtmlToFile();
            }
        });

        searchButton.setOnClickListener(v -> showSearchOverlay());

        searchQueryEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        searchNextButton.setOnClickListener(v -> moveToNextSearchMatch());
        searchPrevButton.setOnClickListener(v -> moveToPreviousSearchMatch());
        closeSearchButton.setOnClickListener(v -> hideSearchOverlay());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            htmlEditText.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (!isUpdating && htmlEditText.getText().length() > LARGE_TEXT_THRESHOLD) {
                    final String currentText = htmlEditText.getText().toString();
                    executor.execute(() -> {
                        final int[][] spans = getHighlightSpans(currentText);
                        uiHandler.post(() -> applyHighlight(htmlEditText.getText(), spans));
                    });
                }
            });
        }
    }

    private void showSearchOverlay() {
        searchOverlay.setVisibility(View.VISIBLE);
        searchButton.setVisibility(View.INVISIBLE);
        searchQueryEditText.requestFocus();
        searchQueryEditText.setText("");
        searchResultCountTextView.setText("件数: 0");
        searchMatchPositions.clear();
        currentSearchIndex = -1;
    }

    private void hideSearchOverlay() {
        searchOverlay.setVisibility(View.GONE);
        searchButton.setVisibility(View.VISIBLE);
        Editable text = htmlEditText.getText();
        Object[] bgSpans = text.getSpans(0, text.length(), BackgroundColorSpan.class);
        for (Object span : bgSpans) {
            text.removeSpan(span);
        }
    }

    private void performSearch(final String query) {
        executor.execute(() -> {
            searchMatchPositions.clear();
            if (query != null && !query.isEmpty()) {
                String text = htmlEditText.getText().toString();
                int index = 0;
                while ((index = text.indexOf(query, index)) >= 0) {
                    searchMatchPositions.add(index);
                    index += query.length();
                }
            }
            final int count = searchMatchPositions.size();
            uiHandler.post(() -> {
                searchResultCountTextView.setText("件数: " + count);
                if (count > 0) {
                    currentSearchIndex = 0;
                    highlightCurrentSearchMatch();
                }
            });
        });
    }

    private void highlightCurrentSearchMatch() {
        Editable text = htmlEditText.getText();
        Object[] bgSpans = text.getSpans(0, text.length(), BackgroundColorSpan.class);
        for (Object span : bgSpans) {
            text.removeSpan(span);
        }
        if (currentSearchIndex >= 0 && currentSearchIndex < searchMatchPositions.size()) {
            final int start = searchMatchPositions.get(currentSearchIndex);
            int end = start + searchQueryEditText.getText().length();
            if (start >= 0 && end <= text.length()) {
                text.setSpan(new BackgroundColorSpan(Color.YELLOW), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                htmlEditText.setSelection(start, end);
                htmlEditText.post(() -> {
                    if (htmlEditText.getLayout() != null) {
                        int line = htmlEditText.getLayout().getLineForOffset(start);
                        int y = htmlEditText.getLayout().getLineTop(line);
                        View parent = (View) htmlEditText.getParent();
                        if (parent instanceof android.widget.ScrollView) {
                            ((android.widget.ScrollView) parent).smoothScrollTo(0, y);
                        } else {
                            htmlEditText.scrollTo(0, y);
                        }
                    }
                });
            }
        }
    }

    private void moveToNextSearchMatch() {
        if (!searchMatchPositions.isEmpty()) {
            currentSearchIndex = (currentSearchIndex + 1) % searchMatchPositions.size();
            highlightCurrentSearchMatch();
        }
    }

    private void moveToPreviousSearchMatch() {
        if (!searchMatchPositions.isEmpty()) {
            currentSearchIndex = (currentSearchIndex - 1 + searchMatchPositions.size()) % searchMatchPositions.size();
            highlightCurrentSearchMatch();
        }
    }

    private void fetchHtml(final String urlString) {
        isLoading = true;
        executor.execute(() -> {
            final StringBuilder result = new StringBuilder();
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
                reader.close();
                uiHandler.post(() -> {
                    originalHtml = result.toString();
                    isEditing = false;
                    editHistory.clear();
                    Editable editable = htmlEditText.getText();
                    editable.clear();
                    editable.append(originalHtml);
                    executor.execute(() -> {
                        final int[][] spans = getHighlightSpans(originalHtml);
                        uiHandler.post(() -> {
                            applyHighlight(htmlEditText.getText(), spans);
                            htmlEditText.setKeyListener(null);
                            isLoading = false;
                        });
                    });
                });
            } catch (final Exception e) {
                uiHandler.post(() -> {
                    Toast.makeText(htmlview.this, "HTMLの取得に失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isLoading = false;
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_HTML && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                readHtmlFromUri(uri);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void readHtmlFromUri(final Uri uri) {
        isLoading = true;
        executor.execute(() -> {
            final StringBuilder sb = new StringBuilder();
            try {
                ContentResolver resolver = getContentResolver();
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) {
                        throw new Exception("InputStreamが取得できません");
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    }
                }
                uiHandler.post(() -> {
                    originalHtml = sb.toString();
                    isEditing = false;
                    editHistory.clear();
                    Editable editable = htmlEditText.getText();
                    editable.clear();
                    editable.append(originalHtml);
                    executor.execute(() -> {
                        final int[][] spans = getHighlightSpans(originalHtml);
                        uiHandler.post(() -> {
                            applyHighlight(htmlEditText.getText(), spans);
                            htmlEditText.setKeyListener(null);
                            isLoading = false;
                        });
                    });
                });
            } catch (final Exception e) {
                uiHandler.post(() -> {
                    Toast.makeText(htmlview.this, "HTMLファイルの読み込みに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isLoading = false;
                });
            }
        });
    }

    private int[][] getHighlightSpans(String text) {
        ArrayList<int[]> spansList = new ArrayList<>();
        boolean isLargeText = text.length() > LARGE_TEXT_THRESHOLD && htmlEditText.getLayout() != null;
        int visibleStart = 0;
        int visibleEnd = text.length();
        if (isLargeText) {
            int firstVisibleLine = htmlEditText.getLayout().getLineForVertical(htmlEditText.getScrollY());
            int lastVisibleLine = htmlEditText.getLayout().getLineForVertical(htmlEditText.getScrollY() + htmlEditText.getHeight());
            visibleStart = htmlEditText.getLayout().getLineStart(firstVisibleLine);
            visibleEnd = htmlEditText.getLayout().getLineEnd(lastVisibleLine);
            text = text.substring(visibleStart, visibleEnd);
        }
        Matcher tagMatcher = TAG_PATTERN.matcher(text);
        while (tagMatcher.find()) {
            int tagStart = (isLargeText ? visibleStart : 0) + tagMatcher.start();
            int tagEnd = (isLargeText ? visibleStart : 0) + tagMatcher.end();
            spansList.add(new int[]{tagStart, tagEnd, TAG_COLOR});
            String tagText = text.substring(tagMatcher.start(), tagMatcher.end());
            Matcher attrMatcher = ATTR_PATTERN.matcher(tagText);
            while (attrMatcher.find()) {
                int attrNameStart = tagStart + attrMatcher.start(1);
                int attrNameEnd = tagStart + attrMatcher.end(1);
                spansList.add(new int[]{attrNameStart, attrNameEnd, ATTRIBUTE_COLOR});
                int attrValueStart = tagStart + attrMatcher.start(2);
                int attrValueEnd = tagStart + attrMatcher.end(2);
                spansList.add(new int[]{attrValueStart, attrValueEnd, VALUE_COLOR});
            }
        }
        int[][] result = new int[spansList.size()][3];
        for (int i = 0; i < spansList.size(); i++) {
            result[i] = spansList.get(i);
        }
        return result;
    }

    private void applyHighlight(Editable editable, int[][] spans) {
        if (spans != null) {
            Object[] oldSpans = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
            for (Object span : oldSpans) {
                editable.removeSpan(span);
            }
            int length = editable.length();
            for (int[] span : spans) {
                int start = span[0];
                int end = span[1];
                int color = span[2];
                if (start >= 0 && end <= length && start < end) {
                    editable.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }

    private void saveHtmlToFile() {
        final String currentText = htmlEditText.getText().toString();
        final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final String fileName = timeStamp + (!currentText.equals(originalHtml) ? "Edit.html" : ".html");
        executor.execute(() -> {
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadDir, fileName);
                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                    bos.write(currentText.getBytes(StandardCharsets.UTF_8));
                    bos.flush();
                }
                uiHandler.post(() -> Toast.makeText(htmlview.this, "保存しました: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (final Exception e) {
                uiHandler.post(() -> Toast.makeText(htmlview.this, "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_WRITE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveHtmlToFile();
        } else if (requestCode == REQUEST_PERMISSION_WRITE) {
            Toast.makeText(this, "書き込み権限が必要です", Toast.LENGTH_SHORT).show();
        }
    }
}
