package com.coara.browser;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QrCodeActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int FILE_SELECT_REQUEST = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MAX_QR_DATA_LENGTH = 2953;
    private static final String TAG = "QrCodeActivity";

    private Button selectFileButton;
    private Button generateTextQrButton;
    private Button scanQrButton;
    private EditText inputEditText;
    private LinearLayoutCompat resultLayout;
    private volatile boolean isProcessing = false;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private boolean previewing = false;
    private Reader reader = new MultiFormatReader();
    private AsyncTask<byte[], Void, Result> decodeTask;
    private boolean scanning = false;

    
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_LIKE_PATTERN = Pattern.compile("^([\\w.-]+\\.[a-z]{2,})(/.*)?$", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        }

        LinearLayoutCompat layout = new LinearLayoutCompat(this);
        layout.setOrientation(LinearLayoutCompat.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        selectFileButton = new Button(this);
        selectFileButton.setText("ファイルを選択してQR生成");
        selectFileButton.setOnClickListener(v -> selectFile());
        layout.addView(selectFileButton);

        inputEditText = new EditText(this);
        inputEditText.setHint("文字列やURLを入力");
        layout.addView(inputEditText);

        generateTextQrButton = new Button(this);
        generateTextQrButton.setText("QRコード保存");
        generateTextQrButton.setVisibility(View.GONE);
        generateTextQrButton.setOnClickListener(v -> generateTextQrCode());
        layout.addView(generateTextQrButton);

        inputEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                generateTextQrButton.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
        });

        scanQrButton = new Button(this);
        scanQrButton.setText("QRコードをスキャン");
        scanQrButton.setOnClickListener(v -> checkCameraPermissionAndScan());
        layout.addView(scanQrButton);

        resultLayout = new LinearLayoutCompat(this);
        resultLayout.setOrientation(LinearLayoutCompat.VERTICAL);
        resultLayout.setVisibility(View.GONE);
        layout.addView(resultLayout);

        surfaceView = new SurfaceView(this);
        surfaceView.setVisibility(View.GONE);
        layout.addView(surfaceView, new LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                LinearLayoutCompat.LayoutParams.MATCH_PARENT
        ));

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        setContentView(layout);
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_SELECT_REQUEST);
    }

    private void checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            startQrScan();
        }
    }

    private void startQrScan() {
        scanning = true;
        surfaceView.setVisibility(View.VISIBLE);
        selectFileButton.setVisibility(View.GONE);
        inputEditText.setVisibility(View.GONE);
        generateTextQrButton.setVisibility(View.GONE);
        scanQrButton.setVisibility(View.GONE);
        resultLayout.setVisibility(View.GONE);
    }

    private void stopQrScan() {
        scanning = false;
        if (previewing) {
            camera.stopPreview();
            camera.setPreviewCallback(null);
            camera.release();
            camera = null;
            previewing = false;
        }
        surfaceView.setVisibility(View.GONE);
        selectFileButton.setVisibility(View.VISIBLE);
        inputEditText.setVisibility(View.VISIBLE);
        if (!inputEditText.getText().toString().trim().isEmpty()) {
            generateTextQrButton.setVisibility(View.VISIBLE);
        }
        scanQrButton.setVisibility(View.VISIBLE);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            camera.setPreviewDisplay(holder);
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPreviewSize(640, 480);
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(parameters);
            camera.setDisplayOrientation(90);
            camera.setPreviewCallback(this);
            camera.startPreview();
            previewing = true;
        } catch (IOException e) {
            Log.e(TAG, "Surface creation failed", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (previewing) {
            camera.stopPreview();
            previewing = false;
        }
        if (camera != null) {
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                previewing = true;
            } catch (IOException e) {
                Log.e(TAG, "Surface change failed", e);
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) {
            camera.stopPreview();
            camera.setPreviewCallback(null);
            camera.release();
            camera = null;
            previewing = false;
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera camera) {
        if (!scanning || decodeTask != null) return;

        Camera.Parameters parameters = camera.getParameters();
        int width = parameters.getPreviewSize().width;
        int height = parameters.getPreviewSize().height;

        decodeTask = new AsyncTask<byte[], Void, Result>() {
            @Override
            protected Result doInBackground(byte[]... params) {
                byte[] frameData = params[0];
                LuminanceSource source = new PlanarYUVLuminanceSource(frameData, width, height, 0, 0, width, height, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                try {
                    return reader.decode(bitmap);
                } catch (NotFoundException | ChecksumException | FormatException e) {
                    return null;
                } finally {
                    reader.reset();
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                decodeTask = null;
                if (result != null) {
                    stopQrScan();
                    displayScanResult(result.getText());
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, data);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_SELECT_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                final String originalFileName = getFileName(fileUri);
                final String qrContent = createDataUri(fileUri);
                if (qrContent == null) {
                    Toast.makeText(this, "ファイルの変換に失敗しました", Toast.LENGTH_LONG).show();
                    return;
                }
                new Thread(() -> {
                    isProcessing = true;
                    Bitmap qrBitmap = generateQrCodeBitmap(qrContent, 500, 500);
                    if (qrBitmap != null) {
                        final String savedFileName = originalFileName + "_" + getCurrentTimeStamp() + ".png";
                        final boolean saved = saveBitmapToPictures(qrBitmap, savedFileName);
                        runOnUiThread(() -> {
                            isProcessing = false;
                            Toast.makeText(this, saved ? "QRコード画像保存: " + savedFileName : "保存に失敗しました", Toast.LENGTH_LONG).show();
                        });
                    } else {
                        runOnUiThread(() -> {
                            isProcessing = false;
                            Toast.makeText(this, "QRコード生成に失敗しました", Toast.LENGTH_LONG).show();
                        });
                    }
                }).start();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void displayScanResult(String content) {
        resultLayout.removeAllViews();
        resultLayout.setVisibility(View.VISIBLE);

        TextView resultText = new TextView(this);
        resultText.setTextSize(18);
        resultText.setTextColor(Color.BLACK);
        resultText.setPadding(0, 8, 0, 8);

        String raw = content != null ? content.trim() : "";
        String extracted = extractHttpUrl(raw);

        if (extracted == null) {
        
            String decoded = decodeRepeatedly(raw, 5);
            if (!decoded.equals(raw)) {
                extracted = extractHttpUrl(decoded);
            }
        }

        if (extracted != null) {
            String normalized = normalizeToHttp(extracted);
            final String pure = sanitizeStripQueryAndFragment(normalized);
            if (pure != null) {
                resultText.setText("URL: " + pure + "\nタイトルを取得中...");
                new FetchTitleTask(resultText).execute(pure);

                resultText.setOnClickListener(v -> {
                    try {
                        Uri u = Uri.parse(pure);
                        String scheme = u.getScheme();
                        if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(pure));
                            startActivity(intent);
                            finish();
                        } else {
                            Toast.makeText(this, "無効なスキームのため開けません", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "URL を開けませんでした", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to open pure url", e);
                    }
                });
            } else {
            
                resultText.setText("テキスト: " + raw + "\n（有効な純粋URLに変換できませんでした）");
                resultText.setOnClickListener(v -> {
                    copyToClipboardAndNotify(raw);
                });
            }
        } else {
    
            resultText.setText("テキスト: " + raw);
            resultText.setOnClickListener(v -> {
                copyToClipboardAndNotify(raw);
            });
        }

        resultText.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("QR Content", content);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "内容をコピーしました", Toast.LENGTH_SHORT).show();
            return true;
        });

        resultLayout.addView(resultText);
    }

    private String extractHttpUrl(String content) {
        if (content == null) return null;
        String trimmed = content.trim();

        if (trimmed.regionMatches(true, 0, "http://", 0, 7) ||
            trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return trimmed;
        }

        Matcher m = HTTP_URL_PATTERN.matcher(trimmed);
        if (m.find()) {
            return m.group(1);
        }

        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }

        if (trimmed.toLowerCase().startsWith("www.")) {
            return "https://" + trimmed;
        }

        Matcher mh = HOST_LIKE_PATTERN.matcher(trimmed);
        if (mh.find()) {
            return "https://" + trimmed;
        }

        return null;
    }


    private String normalizeToHttp(String candidate) {
        if (candidate == null) return null;
        String s = candidate.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return s;
        }
        if (lower.startsWith("//")) {
            return "https:" + s;
        }
        if (lower.startsWith("www.")) {
            return "https://" + s;
        }
        Matcher m = HOST_LIKE_PATTERN.matcher(s);
        if (m.find()) {
            return "https://" + s;
        }
        return null;
    }


    private String sanitizeStripQueryAndFragment(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url.trim());
            String scheme = u.getScheme();
            if (scheme == null) return null;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;

            Uri.Builder b = new Uri.Builder();
            b.scheme(u.getScheme());

            String authority = u.getAuthority();
            if (authority != null && !authority.isEmpty()) {
                b.authority(authority);
            } else {
                String host = u.getHost();
                if (host == null || host.isEmpty()) return null;
                if (u.getPort() != -1) {
                    b.authority(host + ":" + u.getPort());
                } else {
                    b.authority(host);
                }
            }

            String path = u.getPath();
            if (path != null && !path.isEmpty()) {
                b.path(path);
            }

            Uri out = b.build();
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "sanitizeStripQueryAndFragment failed for: " + url, e);
            return null;
        }
    }

    private String decodeRepeatedly(String s, int times) {
        if (s == null) return null;
        String prev = s;
        for (int i = 0; i < times; i++) {
            try {
                String dec = URLDecoder.decode(prev, "UTF-8");
                if (dec.equals(prev)) break;
                prev = dec;
            } catch (Exception e) {
                break;
            }
        }
        return prev;
    }

    private void copyToClipboardAndNotify(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("URL", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "受信した内容をクリップボードにコピーしました", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    private class FetchTitleTask extends AsyncTask<String, Void, String> {
        private TextView textView;

        FetchTitleTask(TextView textView) {
            this.textView = textView;
        }

        @Override
        protected String doInBackground(String... urls) {
            String urlString = urls[0];
            String title = "タイトルが見つかりませんでした";
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.connect();

                InputStream stream = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder buffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line.toLowerCase());
                }
                String html = buffer.toString();

                int titleStart = html.indexOf("<title>");
                if (titleStart != -1) {
                    int titleEnd = html.indexOf("</title>", titleStart);
                    if (titleEnd != -1) {
                        title = html.substring(titleStart + 7, titleEnd).trim();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Title fetch failed", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return title;
        }

        @Override
        protected void onPostExecute(String title) {
            try {
                String current = textView.getText().toString();
                String urlLine = (current != null && current.startsWith("URL: ")) ? current.split("\n")[0].substring(5) : "";
                textView.setText("URL: " + urlLine + "\nタイトル: " + title);
            } catch (Exception e) {
                textView.setText("タイトル: " + title);
            }
        }
    }

    private Bitmap generateQrCodeBitmap(String text, int width, int height) {
        QRCodeWriter writer = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return result != null ? result : (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "unknown");
    }

    private String getCurrentTimeStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    private boolean saveBitmapToPictures(Bitmap bitmap, String fileName) {
        OutputStream out = null;
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                out = getContentResolver().openOutputStream(uri);
            } else {
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File file = new File(picturesDir, fileName);
                out = new FileOutputStream(file);
            }
            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private void generateTextQrCode() {
        final String text = inputEditText.getText().toString().trim();
        if (!text.isEmpty()) {
            new Thread(() -> {
                isProcessing = true;
                Bitmap qrBitmap = generateQrCodeBitmap(text, 500, 500);
                if (qrBitmap != null) {
                    final String savedFileName = getCurrentTimeStamp() + ".png";
                    final boolean saved = saveBitmapToPictures(qrBitmap, savedFileName);
                    runOnUiThread(() -> {
                        isProcessing = false;
                        Toast.makeText(this, saved ? "QRコード画像保存: " + savedFileName : "保存に失敗しました", Toast.LENGTH_LONG).show();
                    });
                } else {
                    runOnUiThread(() -> {
                        isProcessing = false;
                        Toast.makeText(this, "QRコード生成に失敗しました", Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    private String createDataUri(Uri fileUri) {
        String mimeType = getContentResolver().getType(fileUri);
        if (mimeType == null) mimeType = "application/octet-stream";
        String base64Data = convertFileToBase64(fileUri);
        if (base64Data == null) return null;
        String dataUriPrefix = mimeType.startsWith("image/") ? "data:" + mimeType + ";base64," :
                (mimeType.equals("text/plain") ? "data:" + mimeType + ";charset=utf-8;base64," : "data:" + mimeType + ";base64,");
        String dataUri = dataUriPrefix + base64Data;
        if (dataUri.length() > MAX_QR_DATA_LENGTH) {
            runOnUiThread(() -> Toast.makeText(this, "ファイル容量がQRコードのサイズを超えています", Toast.LENGTH_LONG).show());
            return null;
        }
        return dataUri;
    }

    private String convertFileToBase64(Uri fileUri) {
        InputStream inputStream = null;
        ByteArrayOutputStream byteArrayOutputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(fileUri);
            if (inputStream == null) return null;
            BufferedInputStream bis = new BufferedInputStream(inputStream);
            byteArrayOutputStream = new ByteArrayOutputStream();
            Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                base64OutputStream.write(buffer, 0, bytesRead);
            }
            base64OutputStream.flush();
            base64OutputStream.close();
            return byteArrayOutputStream.toString("UTF-8");
        } catch (IOException ignored) {
            return null;
        } finally {
            if (byteArrayOutputStream != null) {
                try {
                    byteArrayOutputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isProcessing || scanning) {
            if (scanning) {
                stopQrScan();
                return;
            }
            Toast.makeText(this, "変換中はバックキーが無効です", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "外部ストレージへの書き込み権限が必要です", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQrScan();
            } else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanning) {
            stopQrScan();
        }
    }
}
