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
                                        .apply();
                            } else {
                                Log.e("OnboardingActivity", "Token exchange failed with code: " + conn.getResponseCode());
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                    try {
                        registerDevice();
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    Log.e("OnboardingActivity", "State mismatch: expected " + state + " but got " + state_output);
                }
            }
        }
        super.onNewIntent(intent);
        setIntent(intent);
        //startActivity(new Intent(this, FileSyncActivity.class));
    }

    private void registerDevice() throws NoSuchAlgorithmException, JSONException {
        String device_id = UUID.randomUUID().toString();
        String device_name = Build.MODEL;
        PublicKey public_key_raw = rsaOaep();
        java.security.interfaces.RSAPublicKey rsaPublicKey = (java.security.interfaces.RSAPublicKey) public_key_raw;
        JSONObject public_key_jwk = new JSONObject();
        public_key_jwk.put("kty", "RSA");
        public_key_jwk.put("alg", "RSA-OAEP-256");
        public_key_jwk.put("use", "enc");
        public_key_jwk.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getModulus().toByteArray()));
        public_key_jwk.put("e", Base64.getUrlEncoder().withoutPadding().encodeToString(rsaPublicKey.getPublicExponent().toByteArray()));
        byte[] userKeyRaw = new byte[32];
        new SecureRandom().nextBytes(userKeyRaw);
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

         Log.e(TAG, "Wrapped user key: " + wrappedUserKey);

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
                        getSharedPreferences("Reg", MODE_PRIVATE).edit()
                                .putBoolean("waiting_for_enrollment", true)
                                .putString("device_id", json.getString("device_id"))
                                .apply();
                        startActivity(new Intent(this, approvalActivity.class));
                        finish();
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Device registration failed: " + json.optString("message", "Unknown error"), Toast.LENGTH_LONG).show();
                        });
                    }

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Device registered successfully", Toast.LENGTH_LONG).show();
                    });
                } else if (conn.getResponseCode() == 401) {
                    refreshToken();
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

    public static String wrapUserKeyForDevice(byte[] authUserKeyRaw, PublicKey devicePublicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.WRAP_MODE, devicePublicKey);

        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(authUserKeyRaw, "AES");
        byte[] wrappedKey = cipher.wrap(secretKey);

        return Base64.getEncoder().encodeToString(wrappedKey);
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
                    Log.e(TAG, "Token exchange response code: " + responseBody);
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

    public PublicKey rsaOaep() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");

        keyPairGen.initialize(2048);

        KeyPair pair = keyPairGen.generateKeyPair();

        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    this,
                    "KeyPrefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            encryptedPrefs.edit()
                    .putString("public_key", android.util.Base64.encodeToString(pair.getPublic().getEncoded(), android.util.Base64.DEFAULT))
                    .putString("private_key", android.util.Base64.encodeToString(pair.getPrivate().getEncoded(), android.util.Base64.DEFAULT))
                    .apply();
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error storing keys in encrypted preferences", e);
        }

        return pair.getPublic();
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