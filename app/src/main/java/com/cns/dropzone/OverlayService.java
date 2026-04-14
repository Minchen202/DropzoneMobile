package com.cns.dropzone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.lang.ref.WeakReference;
import java.util.Locale;

public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "dropzone_sync";
    private static final int NOTIFICATION_ID = 1001;
    private static final long OVERLAY_UPDATE_INTERVAL_MS = 80L;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 300L;
    private static volatile float currentProgress = 0f;
    private static WeakReference<OverlayService> instanceRef = new WeakReference<>(null);

    private WindowManager windowManager;
    private View overlayView;
    private CircularProgressIndicator progressBar;
    private TextView tvPercent;
    private NotificationManager notificationManager;
    private String currentTaskLabel = "Syncing";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressFlushRunnable = this::flushProgress;
    private boolean progressFlushScheduled = false;
    private boolean completionHandled = false;
    private long lastOverlayUpdateMs = 0L;
    private long lastNotificationUpdateMs = 0L;
    private int lastOverlayPercent = -1;
    private int lastNotificationPercent = -1;


    public static void setProgress(float progress) {
        currentProgress = Math.max(0f, Math.min(progress, 1f));
        OverlayService service = instanceRef.get();
        if (service != null) {
            service.scheduleProgressFlush();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRef = new WeakReference<>(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String type = intent != null ? intent.getStringExtra("type") : null;
        if (type == null) return START_NOT_STICKY;

        switch (type) {
            case "PROGRESS":
            case "PROGRESS_DOWNLOAD":
            case "PROGRESS_UPLOAD":
            case "PROGRESS_uploud":
                currentProgress = 0f;
                resetProgressUiState();
                currentTaskLabel = type.contains("UPLOAD") || type.contains("uploud") ? "Uploading" : "Downloading";
                Log.i(TAG, "Start " + currentTaskLabel.toLowerCase() + " with progress overlay");
                startForeground(NOTIFICATION_ID, buildNotification(0));
                showOverlay(currentTaskLabel);
                break;
            case "CLOSE":
                stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    private void showOverlay(String label) {
        removeOverlay();
        Log.i(TAG, "1");

        ContextThemeWrapper themedContext = new ContextThemeWrapper(this, R.style.Theme_Dropping);
        LayoutInflater inflater = LayoutInflater.from(themedContext);
        Log.i(TAG, "2");
        overlayView = inflater.inflate(R.layout.overlay_progress, new FrameLayout(this), false);
        progressBar = overlayView.findViewById(R.id.progress_bar);
        tvPercent = overlayView.findViewById(R.id.tv_percent);

        Log.i(TAG, "Inflated overlay view for " + label);

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 40;

        Log.i(TAG, "Inflated overlay view for " + label);

        windowManager.addView(overlayView, params);
        flushProgress();
    }

    private void scheduleProgressFlush() {
        if (completionHandled) {
            return;
        }

        int percent = Math.round(currentProgress * 100f);
        long now = android.os.SystemClock.uptimeMillis();
        long delay = 0L;

        if (percent < 100) {
            long elapsedSinceOverlayUpdate = now - lastOverlayUpdateMs;
            if (elapsedSinceOverlayUpdate < OVERLAY_UPDATE_INTERVAL_MS) {
                delay = OVERLAY_UPDATE_INTERVAL_MS - elapsedSinceOverlayUpdate;
            }
        }

        if (progressFlushScheduled) {
            return;
        }

        progressFlushScheduled = true;
        handler.postDelayed(progressFlushRunnable, delay);
    }

    private void flushProgress() {
        progressFlushScheduled = false;

        int percent = Math.round(currentProgress * 100f);
        long now = android.os.SystemClock.uptimeMillis();

        if (progressBar != null && tvPercent != null && percent != lastOverlayPercent) {
            progressBar.setProgress(percent);
            tvPercent.setText(String.format(Locale.getDefault(), "%d%%", percent));
            lastOverlayPercent = percent;
            lastOverlayUpdateMs = now;
        }

        if (notificationManager != null
                && percent != lastNotificationPercent
                && (percent >= 100 || now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_INTERVAL_MS)) {
            lastNotificationPercent = percent;
            lastNotificationUpdateMs = now;
        }

        if (percent >= 100 && !completionHandled) {
            completionHandled = true;
            showSuccess();
            Log.i(TAG, "Progress reached 100%, scheduling overlay removal");
            handler.postDelayed(this::stopSelf, 1000);
        }
    }

    private void resetProgressUiState() {
        handler.removeCallbacks(progressFlushRunnable);
        progressFlushScheduled = false;
        completionHandled = false;
        lastOverlayUpdateMs = 0L;
        lastNotificationUpdateMs = 0L;
        lastOverlayPercent = -1;
        lastNotificationPercent = -1;
    }

    public void onOverlayClick(android.view.View view) {
        android.view.View notch = view.findViewById(R.id.imageView);
        if (notch != null) {
            notch.setVisibility(android.view.View.GONE);
            tvPercent.setVisibility(android.view.View.GONE);
            progressBar.setVisibility(android.view.View.GONE);
        }
    }



    private void removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Remove overlay error", e);
            }
            overlayView = null;
            progressBar = null;
            tvPercent = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressFlushRunnable);
        instanceRef = new WeakReference<>(null);
        stopForeground(true);
        removeOverlay();
    }

    private void createNotificationChannel() {
        if (notificationManager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DropZone Sync",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps file uploads and downloads running in the background.");
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int percent) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("DropZone")
                .setContentText(currentTaskLabel + "… " + percent + "%")
                .setOngoing(percent < 100)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(percent, 100)), percent < 100)
                .build();
    }

    private void showSuccess() {
        if (overlayView == null) return;

        CircularProgressIndicator progress = overlayView.findViewById(R.id.progress_bar);
        ImageView checkImageView = overlayView.findViewById(R.id.check_anim_view);

        tvPercent.setVisibility(View.GONE);

        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
        if (overlayView.findViewById(R.id.check_anim_view).getVisibility() != View.INVISIBLE) {
            checkImageView.setVisibility(View.VISIBLE);
        }

        if (checkImageView == null) return;

        checkImageView.setVisibility(View.VISIBLE);

        try {
            checkImageView.setImageResource(R.drawable.avd_check);
            Drawable drawable = checkImageView.getDrawable();
            if (drawable instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) drawable).start();
            }
        } catch (android.content.res.Resources.NotFoundException |
                 android.view.InflateException e) {
            Log.e(TAG, "Failed to load animated check drawable, using fallback.", e);
            checkImageView.setImageResource(android.R.drawable.checkbox_on_background);
        }
    }
}