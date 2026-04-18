package com.example.notificationwebhookapp;

import android.app.Notification;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import org.json.JSONObject;

public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "NotificationListener";
    private static final String PREFS_NAME = "NotificationWebhookPrefs";
    private static final String SELECTED_APPS_KEY = "SelectedApps";
    private static final String BIND_CODE_KEY = "bind_code";
    private static final String SECRET_KEY_KEY = "secret_key";
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String WEBHOOK_URL_KEY = "webhook_url";
    private static final String CONFIG_ENDPOINT = "http://43.133.87.195:9501/api/payment/app/config";
    private static final long DEDUP_WINDOW_MS = 10000; // 10秒内相同通知视为重复

    private String cachedWebhookUrl = null;
    private String cachedSecretKey = null;
    // 防止并发fetch
    private boolean isFetchingConfig = false;
    // 最近发送的通知key和时间戳
    private final Map<String, Long> recentNotifications = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        loadCachedConfig();
    }

    private void loadCachedConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        cachedWebhookUrl = prefs.getString(WEBHOOK_URL_KEY, "");
        cachedSecretKey = prefs.getString(SECRET_KEY_KEY, "");
        Log.d(TAG, "Loaded cached config - webhook_url: " + (cachedWebhookUrl.isEmpty() ? "empty" : "set") + ", secret_key: " + (cachedSecretKey.isEmpty() ? "empty" : "set"));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "Notification posted: " + sbn.getPackageName());

        String packageName = sbn.getPackageName();

        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> selectedApps = sharedPreferences.getStringSet(SELECTED_APPS_KEY, new HashSet<>());

        Log.d(TAG, "Selected apps: " + selectedApps);

        if (selectedApps.isEmpty()) {
            String selectedAppsString = sharedPreferences.getString(SELECTED_APPS_KEY, "");
            if (!selectedAppsString.isEmpty()) {
                String[] appsArray = selectedAppsString.split(",");
                for (String app : appsArray) {
                    selectedApps.add(app.trim());
                }
            }
        }

        if (selectedApps.contains(packageName)) {
            Log.d(TAG, "Notification is from a selected app: " + packageName);

            Notification notification = sbn.getNotification();
            if (notification != null && notification.extras != null) {
                String title = notification.extras.getString(Notification.EXTRA_TITLE);
                String text = notification.extras.getString(Notification.EXTRA_TEXT);

                Log.d(TAG, "Notification details - Title: " + title + ", Text: " + text);

                // 生成通知唯一key
                String notificationKey = packageName + "|" + (title != null ? title : "") + "|" + (text != null ? text : "");

                // 清理过期的去重记录
                long now = System.currentTimeMillis();
                recentNotifications.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_WINDOW_MS);

                // 去重检查
                if (recentNotifications.containsKey(notificationKey)) {
                    Log.d(TAG, "Duplicate notification within " + (DEDUP_WINDOW_MS/1000) + "s, skipping: " + notificationKey);
                    return;
                }
                recentNotifications.put(notificationKey, now);

                sendWebhook(packageName, title, text);

            } else {
                Log.e(TAG, "Notification or extras are null for package: " + packageName);
            }
        } else {
            Log.d(TAG, "Notification is not from a selected app: " + packageName);
        }
    }

    private void sendWebhook(String packageName, String title, String text) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String bindCode = prefs.getString(BIND_CODE_KEY, "");
        final String secretKey = prefs.getString(SECRET_KEY_KEY, "");
        final String deviceId = prefs.getString(DEVICE_ID_KEY, "");

        if (bindCode.isEmpty() || secretKey.isEmpty()) {
            Log.e(TAG, "bind_code or secret_key not configured");
            return;
        }

        // 如果没有缓存的 webhook_url，先获取配置
        if (cachedWebhookUrl == null || cachedWebhookUrl.isEmpty()) {
            if (isFetchingConfig) {
                Log.d(TAG, "Already fetching config, skipping");
                return;
            }
            Log.d(TAG, "No cached webhook_url, fetching config first...");
            isFetchingConfig = true;
            fetchConfigAndSend(packageName, title, text, bindCode, secretKey, deviceId);
            return;
        }

        // 生成签名
        final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        final String sign = generateSign(bindCode, deviceId, secretKey, timestamp);

        String jsonPayload = "{" +
            "\"bind_code\":\"" + escapeJson(bindCode) + "\"," +
            "\"device_id\":\"" + escapeJson(deviceId) + "\"," +
            "\"timestamp\":\"" + timestamp + "\"," +
            "\"sign\":\"" + sign + "\"," +
            "\"package\":\"" + escapeJson(packageName) + "\"," +
            "\"title\":\"" + escapeJson(title != null ? title : "") + "\"," +
            "\"text\":\"" + escapeJson(text != null ? text : "") + "\"" +
            "}";

        Log.d(TAG, "Sending webhook to: " + cachedWebhookUrl);
        Log.d(TAG, "Payload: " + jsonPayload);

        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
                .url(cachedWebhookUrl)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Webhook send failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.body() != null) {
                    String respBody = response.body().string();
                    Log.d(TAG, "Webhook response: " + response.code() + " - " + respBody);
                } else {
                    Log.d(TAG, "Webhook response: " + response.code() + " - empty body");
                }
                if (response.isSuccessful()) {
                    Log.d(TAG, "Webhook sent successfully");
                } else {
                    Log.e(TAG, "Webhook send failed: " + response.code());
                }
            }
        });
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    private void fetchConfigAndSend(String packageName, String title, String text, String bindCode, String secretKey, String deviceId) {
        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        String jsonBody = "{\"bind_code\":\"" + bindCode + "\",\"device_id\":\"" + deviceId + "\"}";
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(CONFIG_ENDPOINT)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch config: " + e.getMessage());
                isFetchingConfig = false;
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                isFetchingConfig = false;
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    Log.d(TAG, "Config response: " + json);

                    try {
                        JSONObject jsonObj = new JSONObject(json);
                        String webhookUrl = jsonObj.optString("webhook_url");
                        if (!webhookUrl.isEmpty()) {
                            cachedWebhookUrl = webhookUrl;
                            Log.d(TAG, "Got webhook_url: " + cachedWebhookUrl);

                            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            prefs.edit().putString(WEBHOOK_URL_KEY, cachedWebhookUrl).apply();

                            sendWebhook(packageName, title, text);
                        } else {
                            Log.e(TAG, "webhook_url is empty in config response");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse config response", e);
                    }
                } else {
                    Log.e(TAG, "Failed to fetch config: " + (response.body() != null ? response.code() : "empty body"));
                }
            }
        });
    }

    private String generateSign(String bindCode, String deviceId, String secretKey, String timestamp) {
        String data = "bind_code=" + bindCode + "&device_id=" + deviceId + "&timestamp=" + timestamp;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Sign generation failed", e);
            return "";
        }
    }
}