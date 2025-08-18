package com.coara.browser;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class QrCodeActivity extends AppCompatActivity {

    private static final int FILE_SELECT_REQUEST = 1;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MAX_QR_DATA_LENGTH = 2953;

    private Button selectFileButton;
    private Button generateTextQrButton;
    private Button scanQrButton;
    private EditText inputEditText;
    private LinearLayoutCompat resultLayout;
    private volatile boolean isProcessing = false;
    private ActivityResultLauncher<ScanOptions> barcodeLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        }

        
        barcodeLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() == null) {
                Toast.makeText(this, "スキャンがキャンセルされました", Toast.LENGTH_SHORT).show();
            } else {
                displayScanResult(result.getContents());
            }
        });

    
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
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("QRコードをスキャン");
        options.setCameraId(0);
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        barcodeLauncher.launch(options);
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
        resultText.setText(isUrl(content) ? "URL: " + content : "テキスト: " + content);
        resultText.setTextSize(14); 
        resultText.setPadding(0, 8, 0, 8);
        resultText.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("url", content);
            startActivity(intent);
            finish(); 
        });
        resultLayout.addView(resultText);
    }

    private boolean isUrl(String content) {
        
        return content != null && (content.startsWith("http://") || content.startsWith("https://"));
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
        if (isProcessing) {
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
}
