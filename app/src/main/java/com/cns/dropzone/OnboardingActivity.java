package com.cns.dropzone;

import android.app.backup.BackupDataOutput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.graphics.shapes.Utils;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;

import javax.crypto.Cipher;

public class OnboardingActivity extends AppCompatActivity {
    private static final String TAG = "OnboardingActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkOverlayPermission();

        SharedPreferences prefs = getSharedPreferences("Login", MODE_PRIVATE);

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

            if (!encryptedPrefs.getString("refresh_token", "").equals("")) {
                //startActivity(new Intent(this, FileSyncActivity.class));
                //finish();
                //return;
            }

        } catch (GeneralSecurityException | IOException e) {
            Log.e("Starting", "Error initializing encrypted preferences", e);
        }

        if (prefs.getBoolean("isLoggedIn", false)) {
            startActivity(new Intent(this, FileSyncActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);
    }

    private static final String ALLOWED_CHARACTERS ="0123456789qwertyuiopasdfghjklzxcvbnm";
    private String state = "";
    private String code_verifier = "";
    static final Random random=new Random();
    private String accessToken = "";

    private static String getRandomToken(final int sizeOfRandomString) {
        final StringBuilder sb=new StringBuilder(sizeOfRandomString);
        for(int i=0;i<sizeOfRandomString;++i)
            sb.append(ALLOWED_CHARACTERS.charAt(random.nextInt(ALLOWED_CHARACTERS.length())));
        return sb.toString();
    }

    public void onLoginCnsClick(android.view.View view) {
        code_verifier = getRandomToken(random.nextInt(85)+43);
        String code_challenge = android.util.Base64.encodeToString(getHash(code_verifier), android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
        getSharedPreferences("Login", MODE_PRIVATE).edit().putString("code_verifier", code_verifier).apply();
        state = getRandomToken(64);
        String authUrl = "https://auth.cns-studios.com/login?" +
                "response_type=code&" +
                "client_id=shareit_android&" +
                "redirect_uri=dropzone://auth/callback&" +
                "code_challenge="+ code_challenge + "&" +
                "code_challenge_method=S256&" +
                "state="+ state +"&" +
                "scope=openid profile";
        androidx.browser.customtabs.CustomTabsIntent.Builder builder = new androidx.browser.customtabs.CustomTabsIntent.Builder();
        androidx.browser.customtabs.CustomTabsIntent customTabsIntent = builder.build();
        customTabsIntent.launchUrl(this, Uri.parse(authUrl));
    }
    @Override
    protected void onNewIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null && "dropzone".equals(uri.getScheme())) {
                String state_output = uri.getQueryParameter("state");

                String serverUrl = "https://auth.cns-studios.com";

                if (state.equals(state_output)) {
                    new Thread(() -> {
                        try {
                            URL url = new URL(serverUrl + "/v2/token");
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);

                            JSONObject body = new JSONObject();
                            body.put("grant_type", "authorization_code");
                            body.put("code", uri.getQueryParameter("code"));
                            body.put("client_id", "shareit_android");
                            body.put("code_verifier", code_verifier);
                            body.put("state", state);
                            body.put("redirect_uri", "dropzone://auth/callback");
                            conn.getOutputStream().write(body.toString().getBytes());

                            if (conn.getResponseCode() == 200) {
                                InputStream inputStream = conn.getInputStream();
                                ByteArrayOutputStream result = new ByteArrayOutputStream();
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = inputStream.read(buffer)) != -1) {
                                    result.write(buffer, 0, length);
                                }
                                JSONObject response = new JSONObject(result.toString(StandardCharsets.UTF_8.name()));
                                String refreshedToken = response.optString("refresh_token", "");
                                MasterKey masterKey = new MasterKey.Builder(OnboardingActivity.this)
                                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                        .build();

                                SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                                        OnboardingActivity.this,
                                        "TokenPrefs",
                                        masterKey,
                                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                                );

                                encryptedPrefs.edit()
                                        .putString("access_token", response.optString("access_token"))
                                        .putString("refresh_token", response.optString("refresh_token"))
                                        .commit();

                                runOnUiThread(() -> {
                                    startActivity(new Intent(this, approvalActivity.class));
                                });

                                refreshToken(refreshedToken);
                            } else {
                                InputStream inputStream = conn.getInputStream();
                                ByteArrayOutputStream result = new ByteArrayOutputStream();
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = inputStream.read(buffer)) != -1) {
                                    result.write(buffer, 0, length);
                                }
                                JSONObject response = new JSONObject(result.toString(StandardCharsets.UTF_8.name()));
                                Log.e("OnboardingActivity", "Token exchange failed with code: " + conn.getResponseCode());
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                } else {
                    Log.e("OnboardingActivity", "State mismatch: expected " + state + " but got " + state_output);
                }
            }
        }
        super.onNewIntent(intent);
        setIntent(intent);
        //startActivity(new Intent(this, FileSyncActivity.class));
    }

    private void refreshToken(String refreshToken) {
        new Thread(() -> {
            try {
                String serverUrl = "https://auth.cns-studios.com";

                if (refreshToken == null || refreshToken.isEmpty()) {
                    Log.e(TAG, "Skipping refresh token call because no refresh token was provided.");
                    return;
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

                    encryptedPrefs.edit().putString("access_token", newAccessToken).commit();
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

                    encryptedPrefs.edit().putString("refresh_token", "").commit();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Refresh token error", e);
            }
        }).start();
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



    public byte[] getHash(String password) {
        MessageDigest digest=null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            e1.printStackTrace();
        }
        digest.reset();
        return digest.digest(password.getBytes());
    }
    static String bin2hex(byte[] data) {
        return String.format("%0" + (data.length*2) + "X", new BigInteger(1, data));
    }


    public void onScanQrCodeClick(android.view.View view) {
        startActivity(new Intent(this, QRcodeActivity.class));
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }
}