package com.cns.dropzone;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRcodeActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final String TAG = "MainActivity";

    private String lastScannedInvalid = null;
    private long lastToastTime = 0;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("Login", MODE_PRIVATE);
        if (isLoggedIn(prefs)) {
            startActivity(new Intent(this, FileSyncActivity.class));
            finish();
            return;
        }

        // Show QR scanner UI
        setContentView(R.layout.activity_qrcode);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        PreviewView previewView = findViewById(R.id.preview_view);
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build();
                BarcodeScanner scanner = BarcodeScanning.getClient(options);

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage != null) {
                        InputImage image = InputImage.fromMediaImage(
                                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                        scanner.process(image)
                                .addOnSuccessListener(barcodes -> {
                                    for (Barcode barcode : barcodes) {
                                        String raw = barcode.getRawValue();
                                        if (raw != null) handleQRScan(raw);
                                    }
                                })
                                .addOnCompleteListener(task -> imageProxy.close());
                    } else {
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void handleQRScan(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            onQRScan(json);
        } catch (Exception e) {
            showWarning("Could not recognize QR code content", raw);
        }
    }

    private void onQRScan(JSONObject json) {
        if (!json.has("owner") || !json.has("api_key") || !json.has("server_url")) {
            showWarning("Could not recognize QR code content", json.toString());
            return;
        }

        new Thread(() -> {
            try {
                String serverUrl = json.getString("server_url");
                String apiKey = json.getString("api_key");
                URL url = new URL(serverUrl + "/desktop/auth/verify?key=" + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                int code = conn.getResponseCode();
                if (code == 200) {
                    runOnUiThread(() -> {
                        Intent intent = new Intent(this, VerifyDeviceActivity.class);
                        intent.putExtra("qr_data", json.toString());
                        startActivity(intent);
                    });
                } else {
                    runOnUiThread(() ->
                            showWarning("Verification failed (code " + code + ")", json.toString()));
                }
            } catch (Exception e) {
                Log.e(TAG, "Network error", e);
                runOnUiThread(() -> showWarning("Network error: " + e.getMessage(), json.toString()));
            }
        }).start();
    }

    private void showWarning(String message, String identifier) {
        long now = System.currentTimeMillis();
        if (!identifier.equals(lastScannedInvalid) || now - lastToastTime > 3000) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            lastScannedInvalid = identifier;
            lastToastTime = now;
        }
    }

    private boolean isLoggedIn(SharedPreferences prefs) {
        return prefs.getBoolean("isLoggedIn", false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}