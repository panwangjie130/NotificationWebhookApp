# NotificationWebhookApp 修改说明

## 修改文件清单

| 原文件路径 | 修改后路径 |
|-----------|-----------|
| `app/src/main/java/com/example/notificationwebhookapp/MainActivity.java` | `modified/MainActivity.java` |
| `app/src/main/java/com/example/notificationwebhookapp/SettingsActivity.kt` | `modified/SettingsActivity.kt` |
| `app/src/main/res/layout/activity_settings.xml` | `modified/activity_settings.xml` |

## 修改内容

### 1. MainActivity.java

**主要改动：**
- 新增常量 `CONFIG_ENDPOINT`：配置接口地址（需要修改为你的服务器地址）
- 新增 `bindCode`：存储绑定码
- 新增 `fetchedWebhookUrl`：动态获取的 webhook URL
- 新增 `fetchConfig()` 方法：启动时从服务器获取 webhook URL
- 修改 `sendWebhookMessage()`：使用 `fetchedWebhookUrl` 而不是 SharedPreferences 中的 URL
- 修改 `handleIntent()`：构造包含 bind_code 的 JSON payload

**JSON payload 格式：**
```json
{
    "bind_code": "PAY:1:1",
    "title": "支付宝收款",
    "text": "收款 0.01 元"
}
```

### 2. SettingsActivity.kt

**主要改动：**
- 把 `webhookUrlEditText` 改为 `bindCodeEditText`
- 保存 key 从 `webhookUrl` 改为 `bind_code`
- 增加输入校验（必须以 `PAY:` 开头）
- 增加状态提示

### 3. activity_settings.xml

**主要改动：**
- 修改界面文案为中文
- 修改 hint 为 `例：PAY:1:1`
- 增加标题和状态提示

## 部署步骤

### 1. 修改 MainActivity.java 中的配置接口地址

找到这行，修改为你的服务器地址：
```java
private static final String CONFIG_ENDPOINT = "https://your-domain.com/api/payment/app/config";
```

### 2. 替换文件

将 `modified/` 目录下的文件复制到对应的源码目录。

### 3. PHP 端新增接口

需要在 TGbot 项目中添加接口：
- `GET/POST /api/payment/app/config`
- 返回格式：
```json
{
    "status": "ok",
    "webhook_url": "https://你的域名/api/payment/notify",
    "enabled": true
}
```

### 4. 编译 APK

在 Gitpod 或本地编译：
```bash
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`
