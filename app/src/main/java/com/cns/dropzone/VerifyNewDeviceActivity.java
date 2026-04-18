package com.cns.dropzone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

public class VerifyNewDeviceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_new_device);

        String device_data = getIntent().getStringExtra("device_data");

        try {
            JSONObject json = new JSONObject(device_data);
            String ownerName = json.optString("owner", "Unknown Device");

            String id = json.optString("Device_ID", "Unknown Device ID");

            String key = json.optString("Key", "Unknown Key");

            String time = json.optString("Request_time", "Unknown Time");

            TextView tvOwner = findViewById(R.id.tv_device_name);
            tvOwner.setText(ownerName);

//            TextView tvId = findViewById(R.id.tv_device_id);
//            tvId.setText(id);
//
//            TextView tvKey = findViewById(R.id.tv_key);
//            tvKey.setText(key);
//
//            TextView tvTime = findViewById(R.id.tv_request_time);
//            tvTime.setText(time);

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
            Toast.makeText(this, "Invalid Device Data", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}