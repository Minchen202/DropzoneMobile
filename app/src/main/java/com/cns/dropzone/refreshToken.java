package com.cns.dropzone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public class refreshToken {
    private static final String TAG = "refreshToken";
    static String serverUrl = "https://auth.cns-studios.com";

    public static void refreshAccessToken(Context context) {
        new Thread(() -> {
            try {
                String serverUrl = "https://auth.cns-studios.com";

                String refreshToken = "";
                try {
                    MasterKey masterKey = new MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            context,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    refreshToken = encryptedPrefs.getString("refresh_token", "");
                } catch (GeneralSecurityException | IOException e) {
                    Log.e("Onboarding", "Error initializing encrypted preferences", e);
                }

                Log.e(TAG, "Attempting to refresh token with refresh token: " + refreshToken);

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

                    MasterKey masterKey = new MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            context,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    encryptedPrefs.edit().putString("access_token", newAccessToken).apply();
                } else {
                    Log.e(TAG, "Failed to refresh token. Server responded with code: " + conn.getResponseCode());
                    MasterKey masterKey = new MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();

                    SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                            context,
                            "TokenPrefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );

                    encryptedPrefs.edit().putString("refresh_token", "").apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "Refresh token error", e);
            }
        }).start();
    }

    private static String readResponseBody(HttpURLConnection conn) throws IOException {
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
}