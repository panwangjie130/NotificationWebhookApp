package com.example.notificationwebhookapp;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.util.Arrays;

import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "NotificationWebhook";
    private ListView appList;
    private List<AppInfo> installedApps;
    private AppListAdapter adapter;
    private TextView statusText;

    private static final String PREFS_NAME = "NotificationWebhookPrefs";
    private static final String SELECTED_APPS_KEY = "SelectedApps";
    private static final String BIND_CODE_KEY = "bind_code";
    private static final String SECRET_KEY_KEY = "secret_key";
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String CHANNEL_ID = "my_channel_id";
    private static final int NOTIFICATION_ID = 1;

    // 服务器配置接口地址
    private static final String CONFIG_ENDPOINT = "http://43.133.87.195:9501/api/payment/app/config";
    // 动态获取的 webhook URL
    private String fetchedWebhookUrl = null;
    private String secretKey = null;
    private String deviceId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appList = (ListView) findViewById(R.id.appList);
        statusText = (TextView) findViewById(R.id.statusText);
        installedApps = getInstalledApps();
        adapter = new AppListAdapter(this, installedApps);
        appList.setAdapter(adapter);

        // 生成或获取设备ID
        deviceId = getAppDeviceId();

        // 设置按钮
        Button settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });

        // 保存应用按钮
        Button saveAppsButton = findViewById(R.id.saveAppsButton);
        saveAppsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSelectedApps();
            }
        });

        // 启用通知监听按钮
        Button enableNotificationsButton = findViewById(R.id.enableNotificationsButton);
        enableNotificationsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkNotificationListenerEnabled();
            }
        });

        // 心跳测试按钮
        Button testHeartbeatButton = findViewById(R.id.testHeartbeatButton);
        testHeartbeatButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testHeartbeat();
            }
        });

        // 电池白名单按钮
        Button batteryWhitelistButton = findViewById(R.id.batteryWhitelistButton);
        batteryWhitelistButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBatteryWhitelist();
            }
        });

        loadSavedAppSelections();
        checkNotificationListenerEnabled();

        // 启动时获取配置
        fetchConfig();
    }

    /**
     * 获取或生成设备ID
     */
    private String getAppDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedDeviceId = prefs.getString(DEVICE_ID_KEY, "");
        if (savedDeviceId.isEmpty()) {
            savedDeviceId = UUID.randomUUID().toString();
            prefs.edit().putString(DEVICE_ID_KEY, savedDeviceId).apply();
        }
        return savedDeviceId;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleIntent(getIntent());
        // 每次恢复时重新获取配置
        fetchConfig();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && "com.example.SEND_WEBHOOK".equals(intent.getAction())) {
            Log.d(TAG, "Intent received to send webhook");
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            String packageName = intent.getStringExtra("package");
            Log.d(TAG, "Webhook details - Title: " + title + ", Text: " + text + ", Package: " + packageName);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String bindCode = prefs.getString(BIND_CODE_KEY, "");
            secretKey = prefs.getString(SECRET_KEY_KEY, "");

            if (bindCode.isEmpty() || secretKey.isEmpty()) {
                Log.e(TAG, "Bind code or secret key not configured");
                return;
            }

            if (fetchedWebhookUrl == null || fetchedWebhookUrl.isEmpty()) {
                Log.e(TAG, "Webhook URL not available, fetching config...");
                fetchConfig();
                return;
            }

            // 构造 JSON payload（带签名）
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String sign = generateSign(bindCode, secretKey, timestamp);
            String jsonPayload = "{\"bind_code\":\"" + bindCode + "\",\"device_id\":\"" + deviceId + "\",\"timestamp\":\"" + timestamp + "\",\"sign\":\"" + sign + "\",\"title\":\"" + (title != null ? title : "") + "\",\"text\":\"" + (text != null ? text : "") + "\"}";
            sendWebhookMessage(jsonPayload);
        }
    }

    /**
     * 生成签名
     */
    private String generateSign(String bindCode, String secretKey, String timestamp) {
        // 签名格式: bind_code=device_id=timestamp 然后用 HMAC-SHA256
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

    /**
     * 心跳测试
     */
    private void testHeartbeat() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String bindCode = prefs.getString(BIND_CODE_KEY, "");
        secretKey = prefs.getString(SECRET_KEY_KEY, "");

        if (bindCode.isEmpty() || secretKey.isEmpty()) {
            Toast.makeText(this, "请先在设置中配置绑定码和密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String sign = generateSign(bindCode, secretKey, timestamp);
        String jsonBody = "{\"bind_code\":\"" + bindCode + "\",\"device_id\":\"" + deviceId + "\",\"timestamp\":\"" + timestamp + "\",\"sign\":\"" + sign + "\",\"action\":\"heartbeat\"}";

        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(CONFIG_ENDPOINT)
                .post(body)
                .build();

        Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
                Log.e(TAG, "Heartbeat failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String respBody = response.body().string();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (response.isSuccessful()) {
                            if (respBody.contains("\"code\":200") || respBody.contains("\"heartbeat_ok\"")) {
                                Toast.makeText(MainActivity.this, "✅ 服务器连接成功！", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "⚠️ 配置无效: " + respBody, Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(MainActivity.this, "❌ 服务器返回错误: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                Log.d(TAG, "Heartbeat response: " + respBody);
            }
        });
    }

    /**
     * 请求电池白名单
     */
    private void requestBatteryWhitelist() {
        try {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show();
            } else {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
                Toast.makeText(this, "请在设置中开启电池白名单", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开电池设置，请手动在系统设置中配置", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Battery whitelist error", e);
        }
    }

    /**
     * 从服务器获取配置
     */
    private void fetchConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String bindCode = prefs.getString(BIND_CODE_KEY, "");

        if (bindCode.isEmpty()) {
            Log.w(TAG, "Bind code not configured, please enter in settings");
            return;
        }

        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        // 发送 device_id 让服务器可以识别设备
        String jsonBody = "{\"bind_code\":\"" + bindCode + "\",\"device_id\":\"" + deviceId + "\"}";
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(CONFIG_ENDPOINT)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch config", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    Log.d(TAG, "Config response: " + json);
                    try {
                        // 解析 JSON 响应
                        if (json.contains("\"status\":\"ok\"")) {
                            // 提取 webhook_url
                            int start = json.indexOf("\"webhook_url\":\"") + 16;
                            int end = json.indexOf("\"", start);
                            if (start > 15 && end > start) {
                                fetchedWebhookUrl = json.substring(start, end);
                                Log.d(TAG, "Webhook URL fetched: " + fetchedWebhookUrl);
                            }

                            // 提取 secret_key（如果返回了新的）
                            if (json.contains("\"secret_key\":\"")) {
                                int skStart = json.indexOf("\"secret_key\":\"") + 15;
                                int skEnd = json.indexOf("\"", skStart);
                                if (skStart > 14 && skEnd > skStart) {
                                    String newSecretKey = json.substring(skStart, skEnd);
                                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                    prefs.edit().putString(SECRET_KEY_KEY, newSecretKey).apply();
                                    secretKey = newSecretKey;
                                    Log.d(TAG, "Secret key updated");
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse config response", e);
                    }
                } else {
                    Log.e(TAG, "Failed to fetch config: " + response.message());
                }
            }
        });
    }

    private List<AppInfo> getInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();

        List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : installedApps) {
            try {
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }

                String appName = appInfo.loadLabel(pm).toString();
                Drawable icon = appInfo.loadIcon(pm);
                String packageName = appInfo.packageName;
                apps.add(new AppInfo(appName, packageName, icon));

            } catch (Exception e) {
                Log.e(TAG, "Error loading app info", e);
            }
        }

        apps.sort((app1, app2) -> app1.name.compareToIgnoreCase(app2.name));
        return apps;
    }

    private void saveSelectedApps() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> selectedAppsSet = new HashSet<>();

        for (AppInfo appInfo : installedApps) {
            if (appInfo.isSelected) {
                selectedAppsSet.add(appInfo.packageName);
            }
        }

        editor.putStringSet(SELECTED_APPS_KEY, selectedAppsSet);
        editor.apply();
        Log.d(TAG, "Selected apps saved: " + selectedAppsSet);

        Toast.makeText(this, "已保存 " + selectedAppsSet.size() + " 个应用", Toast.LENGTH_SHORT).show();

        // 保存后重新获取配置
        fetchConfig();
    }

    private void loadSavedAppSelections() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> selectedAppsSet = prefs.getStringSet(SELECTED_APPS_KEY, new HashSet<>());

        if (selectedAppsSet.isEmpty()) {
            String selectedAppsString = prefs.getString(SELECTED_APPS_KEY, "");
            if (!selectedAppsString.isEmpty()) {
                selectedAppsSet = new HashSet<>(Arrays.asList(selectedAppsString.split(",")));
                SharedPreferences.Editor editor = prefs.edit();
                editor.putStringSet(SELECTED_APPS_KEY, selectedAppsSet);
                editor.apply();
            }
        }

        for (String packageName : selectedAppsSet) {
            for (AppInfo appInfo : installedApps) {
                if (appInfo.packageName.equals(packageName)) {
                    appInfo.isSelected = true;
                    break;
                }
            }
        }
        adapter.notifyDataSetChanged();
        Log.d(TAG, "Loaded saved app selections: " + selectedAppsSet);
    }

    private void checkNotificationListenerEnabled() {
        if (!isNotificationServiceEnabled()) {
            Log.d(TAG, "Notification listener is not enabled. Prompting user to enable it.");
            Toast.makeText(this, "请点击开启通知监听权限", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Log.d(TAG, "Notification listener is already enabled.");
            Toast.makeText(this, "✅ 通知监听权限已开启", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNotificationServiceEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null && !flat.isEmpty()) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName componentName = ComponentName.unflattenFromString(name);
                if (componentName != null && packageName.equals(componentName.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void createAndShowNotification() {
        createNotificationChannel();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Notification Title")
                .setContentText("Notification Content")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        Log.d(TAG, "Notification created and shown");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            Log.d(TAG, "Notification channel created");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                createAndShowNotification();
            } else {
                Log.d(TAG, "Notification permission denied");
            }
        }
    }

    public class AppListAdapter extends BaseAdapter {
        private Context context;
        private List<AppInfo> appInfoList;

        public AppListAdapter(Context context, List<AppInfo> appInfoList) {
            this.context = context;
            this.appInfoList = appInfoList;
        }

        @Override
        public int getCount() {
            return appInfoList.size();
        }

        @Override
        public AppInfo getItem(int position) {
            return appInfoList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.app_list_item, parent, false);
            }

            ImageView appIcon = convertView.findViewById(R.id.appIcon);
            TextView appName = convertView.findViewById(R.id.appName);
            CheckBox appCheckbox = convertView.findViewById(R.id.appCheckbox);

            AppInfo appInfo = getItem(position);
            appIcon.setImageDrawable(appInfo.icon);
            appName.setText(appInfo.name);
            appCheckbox.setChecked(appInfo.isSelected);

            appCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    appInfo.isSelected = isChecked;
                    Log.d(TAG, "App selection changed: " + appInfo.name + " - " + isChecked);
                }
            });

            return convertView;
        }
    }

    public static class AppInfo {
        public String name;
        public String packageName;
        public android.graphics.drawable.Drawable icon;
        public boolean isSelected;

        public AppInfo(String name, String packageName, android.graphics.drawable.Drawable icon) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSelected = false;
        }
    }

    /**
     * 发送 webhook 消息
     */
    private void sendWebhookMessage(String jsonPayload) {
        if (fetchedWebhookUrl == null || fetchedWebhookUrl.isEmpty()) {
            Log.e(TAG, "Webhook URL is not available");
            return;
        }

        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.get("application/json; charset=utf-8");

        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
                .url(fetchedWebhookUrl)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send webhook message", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Webhook message sent successfully");
                } else {
                    Log.e(TAG, "Failed to send webhook message: " + response.message());
                }
            }
        });
    }
}