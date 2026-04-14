package com.cns.dropzone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

public class VerifyDeviceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_device);

        String qrData = getIntent().getStringExtra("qr_data");

        try {
            JSONObject json = new JSONObject(qrData);
            String ownerName = json.optString("owner", "Unknown Device");

            TextView tvOwner = findViewById(R.id.tv_owner_name);
            tvOwner.setText(ownerName);

            Button btnConfirm = findViewById(R.id.btn_confirm);
            Button btnCancel = findViewById(R.id.btn_cancel);

            btnConfirm.setOnClickListener(v -> {
                SharedPreferences prefs = getSharedPreferences("Login", MODE_PRIVATE);
                prefs.edit()
                        .putBoolean("isLoggedIn", true)
                        .putString("owner", ownerName)
                        .putString("api_key", json.optString("api_key"))
                        .putString("server_url", json.optString("server_url"))
                        .apply();
                Toast.makeText(this, "Connected to " + ownerName, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, FileSyncActivity.class));
                finishAffinity();
            });

            btnCancel.setOnClickListener(v -> finish());

        } catch (Exception e) {
            Toast.makeText(this, "Invalid QR data", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}