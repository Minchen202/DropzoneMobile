package com.cns.dropzone;

import android.annotation.SuppressLint;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

public class approvalActivity extends AppCompatActivity {

    private WebSocket webSocket;
    private OkHttpClient wsClient;
    private String accessToken = "";
    private SharedPreferences encryptedPrefs;
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

            encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
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

        try {
            registerDevice("");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
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
    private void startEnrollment() {
        new Thread(() -> {
            try {
                String serverUrl = "https://shareit.cns-studios.com";
                String device_id = encryptedPrefs.getString("device_id", null);
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
                    if (json.optString("type", "").equals("enrollment_status")) {
                        JSONObject enrollment = json.optJSONObject("enrollment");
                        String status = enrollment != null ? enrollment.optString("status", "") : "";
                        if (status.equals("approved")) {
                            onEnrollmentApproved();
                        } else if (status.equals("rejected")) {
                            onEnrollmentRejected("Device enrollment rejected.");
                        }
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

    public PublicKey rsaOaep() throws NoSuchAlgorithmException {
        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            SharedPreferences keyPrefs = EncryptedSharedPreferences.create(
                    this,
                    "KeyPrefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            // ✅ Reuse existing key pair if already generated
            String existingPublicKeyB64 = keyPrefs.getString("public_key", "");
            String existingPrivateKeyB64 = keyPrefs.getString("private_key", "");

            if (existingPublicKeyB64 != null && !existingPublicKeyB64.isEmpty()
                    && existingPrivateKeyB64 != null && !existingPrivateKeyB64.isEmpty()) {
                byte[] publicKeyBytes = android.util.Base64.decode(existingPublicKeyB64, android.util.Base64.DEFAULT);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyBytes));
            }

            // No existing key — generate a fresh pair and persist it
            KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            KeyPair pair = keyPairGen.generateKeyPair();

            keyPrefs.edit()
                    .putString("public_key", android.util.Base64.encodeToString(
                            pair.getPublic().getEncoded(), android.util.Base64.DEFAULT))
                    .putString("private_key", android.util.Base64.encodeToString(
                            pair.getPrivate().getEncoded(), android.util.Base64.DEFAULT))
                    .apply();

            return pair.getPublic();

        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error in rsaOaep()", e);
            // Fall through to generate a new pair if prefs are inaccessible
            KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            return keyPairGen.generateKeyPair().getPublic();
        }
    }
    public static String wrapUserKeyForDevice(byte[] authUserKeyRaw, PublicKey devicePublicKey) throws Exception {
        javax.crypto.spec.OAEPParameterSpec spec = new javax.crypto.spec.OAEPParameterSpec(
                "SHA-256", "MGF1",
                new MGF1ParameterSpec("SHA-256"),  // true RSA-OAEP-256
                javax.crypto.spec.PSource.PSpecified.DEFAULT
        );
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, devicePublicKey, spec);
        byte[] wrapped = cipher.doFinal(authUserKeyRaw);
        return java.util.Base64.getEncoder().encodeToString(wrapped);
    }
    private PrivateKey getDevicePrivateKeyFromPrefs() throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        SharedPreferences keyPrefs = EncryptedSharedPreferences.create(
                this,
                "KeyPrefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );

        String privateKeyB64 = keyPrefs.getString("private_key", "");
        if (privateKeyB64 == null || privateKeyB64.isEmpty()) {
            throw new GeneralSecurityException("Missing private key in KeyPrefs");
        }

        byte[] privateKeyBytes = android.util.Base64.decode(privateKeyB64, android.util.Base64.DEFAULT);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    }

    public byte[] unwrapUserKeyForDevice(String wrappedUkB64) throws Exception {
        PrivateKey privateKey = getDevicePrivateKeyFromPrefs();
        byte[] wrappedKeyBytes = android.util.Base64.decode(wrappedUkB64, android.util.Base64.DEFAULT);

        OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), PSource.PSpecified.DEFAULT);
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepParams);
            javax.crypto.SecretKey unwrappedKey = (javax.crypto.SecretKey) cipher.unwrap(
                    wrappedKeyBytes,
                    "AES",
                    Cipher.SECRET_KEY
            );
            return unwrappedKey.getEncoded();
        } catch (Exception e) {
            throw new java.security.GeneralSecurityException("Failed to unwrap user key with OAEP/SHA-256", e);
        }
    }

public byte[] unwrapUserKeyEnvelope(JSONObject envelope) throws Exception {
    return unwrapUserKeyForDevice(envelope.getString("wrapped_uk_b64"));
}

    private void registerDevice(String userkey_storage) throws NoSuchAlgorithmException, JSONException {
        String device_id = encryptedPrefs.getString("device_id", UUID.randomUUID().toString());
        String device_name = Build.MODEL;
        PublicKey public_key_raw = rsaOaep();
        java.security.interfaces.RSAPublicKey rsaPublicKey = (java.security.interfaces.RSAPublicKey) public_key_raw;
        JSONObject public_key_jwk = new JSONObject();
        public_key_jwk.put("kty", "RSA");
        public_key_jwk.put("alg", "RSA-OAEP-256");
        public_key_jwk.put("use", "enc");
        public_key_jwk.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getModulus().toByteArray()));
        public_key_jwk.put("e", Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getPublicExponent().toByteArray()));

        encryptedPrefs.edit().putString("device_id", device_id).apply();

        byte[] userKeyRaw;
        if (userkey_storage.equals("")) {
            userKeyRaw = new byte[32];
            new SecureRandom().nextBytes(userKeyRaw);
            encryptedPrefs.edit().putString("userKeyRaw", Arrays.toString(userKeyRaw))
                    .apply();
        } else {
            Log.e(TAG, "Using existing user key from storage: " + userkey_storage);
            String[] byteStrings = userkey_storage.replaceAll("[\\[\\]]", "").split(", ");
            userKeyRaw = new byte[byteStrings.length];
            for (int i = 0; i < byteStrings.length; i++) {
                userKeyRaw[i] = Byte.parseByte(byteStrings[i]);
            }
            Log.e(TAG, "Using existing user key from storage: " + Arrays.toString(userKeyRaw));
        }

        Log.e(TAG, "Storing wrapped user key and metadata in encrypted preferences" + Arrays.toString(userKeyRaw));

        String wrappedUserKey = "";
        try {
            wrappedUserKey = wrapUserKeyForDevice(userKeyRaw, public_key_raw);
        } catch (Exception e) {
            Log.e(TAG, "Error wrapping user key", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Device registration failed: Key wrapping error", Toast.LENGTH_LONG).show();
            });
            return;
        }
        JSONObject uk_wrap_meta = new JSONObject();
        uk_wrap_meta.put("type", "self-wrap");
        uk_wrap_meta.put("device_id", device_id);
        String finalWrappedUserKey = wrappedUserKey;
        new Thread(() -> {
            try {
                String serverUrl = "https://shareit.cns-studios.com";
                URL url = new URL(serverUrl + "/android/me/devices/register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setDoOutput(true);

                JSONObject Body = new JSONObject();
                Body.put("device_id", device_id);
                Body.put("device_label", device_name);
                Body.put("public_key_jwk", public_key_jwk);
                Body.put("key_algorithm","RSA-OAEP-2048");
                Body.put("key_version", 1);
                Body.put("wrapped_user_key_b64", finalWrappedUserKey.toString());
                Body.put("uk_wrap_alg", "RSA-OAEP-2048-v1");
                Body.put("uk_wrap_meta", uk_wrap_meta);

                Log.e(TAG, "Device registration body: " + Body.toString());

                conn.getOutputStream().write(Body.toString().getBytes());

                if (conn.getResponseCode() == 200) {
                    String responseBody = readResponseBody(conn);
                    JSONObject json = new JSONObject(responseBody);
                    Log.i(TAG, "Device registration response: " + responseBody);
                    if (json.getBoolean("needs_enrollment")) {
                        startEnrollment();
                    } else {
                        byte[] unwraped = unwrapUserKeyEnvelope(json.getJSONObject("user_key_envelope"));
                        encryptedPrefs.edit().putString("userKeyRaw", Arrays.toString(unwraped)).apply();
                        runOnUiThread(() -> {;
                            startActivity(new Intent(approvalActivity.this, FileSyncActivity.class));
                        });
                    }

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Device registered successfully", Toast.LENGTH_LONG).show();
                    });
                } else if (conn.getResponseCode() == 401) {
                    Log.e(TAG, "Device registration failed with 401 Unauthorized. Attempting token refresh.");
                    refreshToken.refreshAccessToken(approvalActivity.this, () -> {
                        runOnUiThread(() -> {
                            try {
                                registerDevice("");
                            } catch (Exception e) {
                                Log.e(TAG, "Error retrying device registration after refresh", e);
                            }
                        });
                    });
                } else {
                    Log.e(TAG, "Device registration failed with code: " + conn.getResponseCode());
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Get DEK failed", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Device registration error", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Device registration failed", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void refreshToken() {
        new Thread(() -> {
            try {
                String serverUrl = "https://auth.cns-studios.com";

                String refreshToken = "";
                try {
                    MasterKey masterKey = new MasterKey.Builder(this)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            this,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    refreshToken = encryptedPrefs.getString("refresh_token", "");
                } catch (GeneralSecurityException | IOException e) {
                    Log.e("Onboarding", "Error initializing encrypted preferences", e);
                }

                URL url = new URL(serverUrl + "/v2/token/refresh");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("refresh_token", refreshToken);
                body.put("client_id", "shareit_android");
                conn.getOutputStream().write(body.toString().getBytes());


                if (conn.getResponseCode() == 200) {
                    String responseBody = readResponseBody(conn);
                    JSONObject json = new JSONObject(responseBody);
                    String newAccessToken = json.getString("access_token");

                    MasterKey masterKey = new MasterKey.Builder(this)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            this,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    encryptedPrefs.edit().putString("access_token", newAccessToken).apply();
                    accessToken = newAccessToken;

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Token refreshed.", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    Log.e(TAG, "Failed to refresh token. Server responded with code: " + conn.getResponseCode());
                    MasterKey masterKey = new MasterKey.Builder(this)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            this,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    encryptedPrefs.edit().putString("refresh_token", "").apply();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Refresh token error", e);
            }
        }).start();
    }
    private void onEnrollmentNoLongerPending() {
        Log.e(TAG, "Enrollment no longer pending according to poll. Stopping polling and waiting for WebSocket update...");
        runOnUiThread(() -> Toast.makeText(this, "Enrollment no longer pending. Re-checking state...", Toast.LENGTH_SHORT).show());
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
    }

    private void onEnrollmentApproved() throws JSONException, NoSuchAlgorithmException {
        if (webSocket != null) {
            webSocket.close(1000, "Approved");
            webSocket = null;
        }
        registerDevice(encryptedPrefs.getString("userKeyRaw", ""));
    }

    private void onEnrollmentRejected(String message) {
        Log.e(TAG, "Enrollment rejected: " + message);
        pollingHandler.removeCallbacks(pendingApprovalsPoller);
        if (webSocket != null) {
            webSocket.close(1000, "Terminal status");
            webSocket = null;
        }
        encryptedPrefs.edit().clear().apply();
        runOnUiThread(() -> {
            setContentView(R.layout.activity_waiting_approval_deny);
            findViewById(R.id.btn_okay).setOnClickListener(v -> {
                startActivity(new Intent(approvalActivity.this, OnboardingActivity.class));
                finish();
            });
        });

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
