package com.cns.dropzone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class OnboardingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkOverlayPermission();

        SharedPreferences prefs = getSharedPreferences("Login", MODE_PRIVATE);
        if (prefs.getBoolean("isLoggedIn", false)) {
            startActivity(new Intent(this, FileSyncActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);
    }

    public void onLoginCnsClick(android.view.View view) {
        Intent intent = new Intent(this, FileSyncActivity.class);
        startActivity(intent);
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