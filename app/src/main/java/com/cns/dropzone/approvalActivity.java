package com.cns.dropzone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

public class approvalActivity extends AppCompatActivity {

    private WebSocket webSocket;
    private OkHttpClient wsClient;
    private String accessToken = "";
    private String enrollmentId = "";
    private static final String TAG = "WaitingApprovalActivity";
    private static final long POLL_INTERVAL_MS = 10_000L;
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingApprovalsPoller = new Runnable() {
        @Override
        public void run() {
            requestPendingApprovals();
            pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_waiting_approval);

        try {
            androidx.security.crypto.MasterKey masterKey = new androidx.security.crypto.MasterKey.Builder(this)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build();

            SharedPreferences encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    this,
                    "TokenPrefs",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            accessToken = encryptedPrefs.getString("access_token", "");
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error initializing encrypted preferences", e);
        }

        SharedPreferences prefs = getSharedPreferences("Reg", MODE_PRIVATE);
        String device_id = prefs.getString("device_id", null);

        if (device_id == null || device_id.isEmpty()) {
            Toast.makeText(this, "Missing device_id. Please register again.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        new Thread(() -> {
            try {
                String serverUrl = "https://shareit.cns-studios.com";
                URL url = new URL(serverUrl + "/android/me/devices/enrollments");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject Body = new JSONObject();
                Body.put("request_device_id", device_id);

                conn.getOutputStream().write(Body.toString().getBytes(StandardCharsets.UTF_8));

                if (conn.getResponseCode() == 200) {
                    String responseBody = readResponseBody(conn);
                    JSONObject json = new JSONObject(responseBody);
                    enrollmentId = json.optString("enrollment_id", "");
                    getSharedPreferences("Reg", MODE_PRIVATE).edit()
                            .putString("enrollment_id", enrollmentId)
                            .putString("verification_code", json.getString("verification_code"))
                            .putString("expires_at", json.getString("expires_at"))
                            .apply();

                    if (!enrollmentId.isEmpty()) {
                        connectEnrollmentWebSocket(enrollmentId);
                    }
                    startPendingApprovalsPolling();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Enrollment created. Waiting for approval...", Toast.LENGTH_LONG).show();
                    });
                } else if (conn.getResponseCode() == 401) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Access token expired, refreshing...", Toast.LENGTH_LONG).show();
                    });
                    refreshToken.refreshAccessToken(this);
                } else {
                    Log.e(TAG, "Enrollment create failed with code: " + conn.getResponseCode() + ", body=" + readErrorBody(conn));
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Enrollment create failed", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Enrollment create error", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Enrollment create failed", Toast.LENGTH_LONG).show();
                });
            }
        }).start();

    }
    private void startPendingApprovalsPolling() {
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
        pollingHandler.post(pendingApprovalsPoller);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
        if (webSocket != null) {
            webSocket.close(1000, "Activity destroyed");
            webSocket = null;
        }
        if (wsClient != null) {
            wsClient.dispatcher().executorService().shutdown();
            wsClient = null;
        }
    }

    private void requestPendingApprovals() {
        new Thread(() -> {
            try {
                String serverUrl = "https://shareit.cns-studios.com";
                URL url = new URL(serverUrl + "/android/me/devices/enrollments/pending");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                if (conn.getResponseCode() == 200) {
                    String responseBody = readResponseBody(conn);
                    JSONObject json = new JSONObject(responseBody);
                    Log.d(TAG, "Pending approvals response: " + json);
                    if (json.getJSONArray("items").length() == 0) {
                        onEnrollmentNoLongerPending();
                    }

                } else if (conn.getResponseCode() == 401) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Access token expired, refreshing...", Toast.LENGTH_LONG).show();
                    });
                    refreshToken.refreshAccessToken(this);
                } else {
                    Log.e(TAG, "Pending approvals request failed with code: " + conn.getResponseCode() + ", body=" + readErrorBody(conn));

                }
            } catch (Exception e) {
                Log.e(TAG, "Pending approvals request error", e);
            }
        }).start();
    }

    private void connectEnrollmentWebSocket(String enrollmentId) {
        if (enrollmentId == null || enrollmentId.isEmpty()) {
            return;
        }

        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
            webSocket = null;
        }

        if (wsClient == null) {
            wsClient = new OkHttpClient();
        }

        String encodedId = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            encodedId = URLEncoder.encode(enrollmentId, StandardCharsets.UTF_8);
        }
        String serverUrl = "wss://shareit.cns-studios.com/android/me/devices/enrollments/" + encodedId + "/ws";
        Request request = new Request.Builder()
                .url(serverUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "Enrollment websocket opened");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "Enrollment websocket message: " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type", "");
                    if ("enrollment_status_snapshot".equals(type)) {
                        handleEnrollmentStatus(json.optString("status", ""));
                        return;
                    }

                    if ("enrollment_status".equals(type)) {
                        JSONObject enrollment = json.optJSONObject("enrollment");
                        handleEnrollmentStatus(enrollment != null ? enrollment.optString("status", "") : "");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing WebSocket message", e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "Enrollment websocket failed", t);
            }
        });
    }

    private void handleEnrollmentStatus(String status) {
        if (status == null) {
            return;
        }

        switch (status.toLowerCase()) {
            case "approved":
                onEnrollmentApproved();
                break;
            case "rejected":
                onEnrollmentRejectedOrExpired("Enrollment rejected");
                break;
            case "expired":
                onEnrollmentRejectedOrExpired("Enrollment expired");
                break;
            default:
                // Pending or unknown status: keep waiting.
                break;
        }
    }

    private void onEnrollmentNoLongerPending() {
        runOnUiThread(() -> Toast.makeText(this, "Enrollment no longer pending. Re-checking state...", Toast.LENGTH_SHORT).show());
        onEnrollmentApproved();
    }

    private void onEnrollmentApproved() {
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
        if (webSocket != null) {
            webSocket.close(1000, "Approved");
            webSocket = null;
        }
        runOnUiThread(() -> Toast.makeText(this, "Device enrollment approved!", Toast.LENGTH_LONG).show());

        runOnUiThread(() -> {
            startActivity(new Intent(approvalActivity.this, FileSyncActivity.class));
            finish();
        });
    }

    private void onEnrollmentRejectedOrExpired(String message) {
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
        if (webSocket != null) {
            webSocket.close(1000, "Terminal status");
            webSocket = null;
        }
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private String readResponseBody(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String readErrorBody(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

}
