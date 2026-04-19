package com.cns.dropzone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cns.dropzone.model.FileItem;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FileSyncActivity extends AppCompatActivity {

    private static final String TAG = "FileSyncActivity";
    private SharedPreferences prefs;
    private FileAdapter adapter;
    private final List<FileItem> fileList = new ArrayList<>();

    private OkHttpClient wsClient;
    private okhttp3.WebSocket webSocket;

    private void connectWebSocket() {
        String serverUrl = prefs.getString("server_url", "");
        String apiKey = prefs.getString("api_key", "");

        String wsUrl = serverUrl.replaceFirst("^http", "ws") + "/api/me/devices/ws";

        wsClient = new OkHttpClient();
        Request request = new Request.Builder()
                .url(wsUrl)
                .addHeader("X-API-KEY", apiKey)
                .build();

        webSocket = wsClient.newWebSocket(request, new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket connected");
            }

            @Override
            public void onMessage(okhttp3.WebSocket webSocket, String text) {
                Log.i(TAG, "WS message: " + text);
                runOnUiThread(() -> loadFiles());
            }

            @Override
            public void onFailure(okhttp3.WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error", t);
            }

            @Override
            public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + reason);
            }
        });
    }

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) uploadFile(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_sync);

        prefs = getSharedPreferences("Login", MODE_PRIVATE);

        connectWebSocket();

        // Device name header
        TextView deviceName = findViewById(R.id.tv_device_name);
        deviceName.setText(prefs.getString("owner", "Unknown Device"));

        // RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recycler_files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter(fileList, this::downloadFile, this::openFile);
        recyclerView.setAdapter(adapter);

        // FAB
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_upload);
        fab.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));

        ExtendedFloatingActionButton tempFab = findViewById(R.id.temp);
        tempFab.setOnClickListener(v -> {
            setContentView(R.layout.activity_waiting_approval);
            TextView approvalDescText = findViewById(R.id.approval_desc_text);
            approvalDescText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        });

        loadFiles();
    }

    private void refreshFiles() {
        loadFiles();
    }

    private void loadFiles() {
        new Thread(() -> {
            try {
                String serverUrl = prefs.getString("server_url", "");
                String apiKey = prefs.getString("api_key", "");
                File dropZoneDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "DropZone"
                );
                String[] localFileNames = dropZoneDir.exists() ? dropZoneDir.list() : new String[0];

                URL url = new URL(serverUrl + "/desktop/files");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-API-KEY", apiKey);

                if (conn.getResponseCode() == 200) {
                    String body = readResponseBody(conn);
                    JSONArray arr = new JSONArray(body);
                    List<FileItem> items = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        if (Arrays.stream(localFileNames).anyMatch(name -> {
                            try {
                                return name.equals(obj.getString("file_name"));
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                        })) {
                            items.add(new FileItem(
                                    obj.getString("file_name"),
                                    formatSize(obj.getLong("file_size")),
                                    timeAgo(obj.getString("uploaded_at")),
                                    obj.getString("id"),
                                    true
                            ));
                        } else {
                            items.add(new FileItem(
                                    obj.getString("file_name"),
                                    formatSize(obj.getLong("file_size")),
                                    timeAgo(obj.getString("uploaded_at")),
                                    obj.getString("id"),
                                    false
                            ));
                        }
                    }
                    runOnUiThread(() -> {
                        fileList.clear();
                        fileList.addAll(items);
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Load files error", e);
            }
        }).start();
    }

    private void newDeviceAlert(JSONObject device) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("New Device Detected")
                .setMessage("A new device has connected to your account. Do you want to refresh the file list?")
                .setPositiveButton("Refresh", (dialog, which) -> loadFiles())
                .setNegativeButton("Ignore", null)
                .show();
    }

    private void openFile(FileItem file) {
        File localFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DropZone/" + file.getName()
        );
        if (!localFile.exists()) {
            Toast.makeText(this, "File not found locally. Please download first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    localFile
            );

            String extension = MimeTypeMap.getFileExtensionFromUrl(localFile.getName());
            String mimeType = extension != null
                    ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase())
                    : null;
            if (mimeType == null) mimeType = "*/*";

            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Open file error", e);
            Toast.makeText(this, "No app found to open this file type.", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadFile(Uri uri) {
        startTransferOverlayService("PROGRESS_UPLOAD");

        new Thread(() -> {
            try {
                String fileName = "unknown";
                long fileSize = 0;
                try (android.database.Cursor cursor = getContentResolver()
                        .query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int si = cursor.getColumnIndex(OpenableColumns.SIZE);
                        fileName = cursor.getString(ni);
                        fileSize = cursor.getLong(si);
                    }
                }

                int chunkSize = 2 * 1024 * 1024;
                int totalChunks = (int) Math.max(1, (fileSize + chunkSize - 1) / chunkSize);
                String serverUrl = prefs.getString("server_url", "");
                String apiKey = prefs.getString("api_key", "");

                // Init upload
                URL initUrl = new URL(serverUrl + "/desktop/upload/init");
                HttpURLConnection initConn = (HttpURLConnection) initUrl.openConnection();
                initConn.setRequestMethod("POST");
                initConn.setRequestProperty("X-API-KEY", apiKey);
                initConn.setRequestProperty("Content-Type", "application/json");
                initConn.setDoOutput(true);

                JSONObject initBody = new JSONObject();
                initBody.put("file_name", fileName);
                initBody.put("file_size", fileSize);
                initBody.put("total_chunks", totalChunks);
                initBody.put("chunk_size", chunkSize);
                initConn.getOutputStream().write(initBody.toString().getBytes());

                if (initConn.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                String resp = readResponseBody(initConn);
                String sessionId = new JSONObject(resp).getString("session_id");

                // Upload chunks
                OkHttpClient client = new OkHttpClient();
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    if (inputStream == null) return;
                    byte[] buffer = new byte[chunkSize];
                    int chunkIndex = 0;
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                        RequestBody filePart = RequestBody.create(
                                chunk, MediaType.parse("application/octet-stream"));
                        RequestBody requestBody = new MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("session_id", sessionId)
                                .addFormDataPart("chunk_index", String.valueOf(chunkIndex))
                                .addFormDataPart("chunk", "chunk_" + chunkIndex + ".bin", filePart)
                                .build();

                        Request request = new Request.Builder()
                                .url(serverUrl + "/desktop/upload/chunk")
                                .post(requestBody)
                                .addHeader("X-API-KEY", apiKey)
                                .build();

                        try (Response response = client.newCall(request).execute()) {
                            if (!response.isSuccessful()) {
                                Log.e(TAG, "Chunk " + chunkIndex + " failed");
                            }
                        }
                        OverlayService.setProgress((float) (chunkIndex + 1) / totalChunks);
                        chunkIndex++;
                    }
                }

                // Complete upload
                URL completeUrl = new URL(serverUrl + "/desktop/upload/complete");
                HttpURLConnection completeConn = (HttpURLConnection) completeUrl.openConnection();
                completeConn.setRequestMethod("POST");
                completeConn.setRequestProperty("X-API-KEY", apiKey);
                completeConn.setRequestProperty("Content-Type", "application/json");
                completeConn.setDoOutput(true);
                JSONObject completeBody = new JSONObject();
                completeBody.put("session_id", sessionId);
                completeBody.put("confirmed", true);
                completeConn.getOutputStream().write(completeBody.toString().getBytes());
                completeConn.getResponseCode(); // trigger request

                OverlayService.setProgress(1f);

                String finalFileName = fileName;
                runOnUiThread(() ->
                        Toast.makeText(this, "Upload complete: " + finalFileName, Toast.LENGTH_SHORT).show());
                loadFiles();

            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Upload error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void downloadFile(FileItem file) {
        String serverUrl = prefs.getString("server_url", "");
        String apiKey = prefs.getString("api_key", "");
        String downloadUrl = serverUrl + "/desktop/files/" + file.getId() + "/download";

        Log.i("Download", "Starting download: " + file.getName() + " from " + downloadUrl + " with API key ");

        startTransferOverlayService("PROGRESS_DOWNLOAD");

        new Thread(() -> {
            try {
                Log.i("Download", "Initiating HTTP connection to download file");
                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-API-KEY", apiKey);

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    Log.i("Download", "HTTP connection established, starting to read file stream");
                    long totalSize = conn.getContentLengthLong();

                    Uri savedUri = null;
                    java.io.OutputStream outStream;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.getName());
                        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                        values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/DropZone");
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

                        savedUri = getContentResolver().insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (savedUri == null) throw new IOException("Failed to create destination file");
                        outStream = getContentResolver().openOutputStream(savedUri);
                        if (outStream == null) throw new IOException("Failed to open destination stream");
                    } else {
                        File downloadsDir = Environment
                                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        File dropZoneDir = new File(downloadsDir, "DropZone");
                        if (!dropZoneDir.exists() && !dropZoneDir.mkdirs()) {
                            throw new IOException("Failed to create DropZone directory");
                        }
                        File outputFile = new File(dropZoneDir, file.getName());
                        outStream = new FileOutputStream(outputFile);
                    }

                    try (InputStream in = conn.getInputStream();
                         java.io.OutputStream out = outStream) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalRead = 0;
                        int lastReportedPercent = -1;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                            if (totalSize > 0) {
                                int percent = (int) ((totalRead * 100L) / totalSize);
                                if (percent != lastReportedPercent) {
                                    OverlayService.setProgress(percent / 100f);
                                    lastReportedPercent = percent;
                                }
                            }
                        }
                    }

                    OverlayService.setProgress(1f);

                    if (savedUri != null) {
                        android.content.ContentValues done = new android.content.ContentValues();
                        done.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(savedUri, done, null, null);
                    }
                    loadFiles();
                } else {
                    Log.e("Download", "Server returned non-OK code: " + conn.getResponseCode());
                }
            } catch (Exception e) {
                Log.e("Download", "Download error", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Download error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void startTransferOverlayService(String type) {
        Intent serviceIntent = new Intent(this, OverlayService.class);
        serviceIntent.putExtra("type", type);
        ContextCompat.startForegroundService(this, serviceIntent);
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

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }

    @Override
    protected void onDestroy() {
        if (webSocket != null) webSocket.close(1000, "Activity destroyed");
        if (wsClient != null) wsClient.dispatcher().executorService().shutdown();
        super.onDestroy();
    }


    public static String timeAgo(String timestamp) {
        long time = Instant.parse(timestamp).toEpochMilli();
        long diff = (System.currentTimeMillis() - time) / 1000;
        if (diff < 10) return "just now";
        if (diff < 60) return diff + "s ago";
        if (diff < 3600) return (diff / 60) + " min ago";
        if (diff < 86400) return (diff / 3600) + "h ago";
        return (diff / 86400) + "d ago";
    }
}