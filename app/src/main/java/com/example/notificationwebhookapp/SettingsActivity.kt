package com.example.notificationwebhookapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 启用 ActionBar 返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"

        sharedPreferences = getSharedPreferences("NotificationWebhookPrefs", Context.MODE_PRIVATE)

        val titleText = findViewById<TextView>(R.id.titleText)
        val bindCodeEditText = findViewById<EditText>(R.id.bindCodeEditText)
        val secretKeyEditText = findViewById<EditText>(R.id.secretKeyEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        // 设置标题
        titleText.text = "收款通知转发配置"

        // 加载保存的绑定码和密钥
        bindCodeEditText.setText(sharedPreferences.getString("bind_code", ""))
        secretKeyEditText.setText(sharedPreferences.getString("secret_key", ""))

        // 检查配置状态
        val bindCode = sharedPreferences.getString("bind_code", "")
        val secretKey = sharedPreferences.getString("secret_key", "")
        if (bindCode.isNullOrEmpty()) {
            statusText.text = "状态：未配置"
        } else if (secretKey.isNullOrEmpty()) {
            statusText.text = "状态：已配置绑定码，缺少密钥"
        } else {
            statusText.text = "状态：已完整配置 ✅"
        }

        saveButton.setOnClickListener {
            val bindCode = bindCodeEditText.text.toString().trim()
            val secretKey = secretKeyEditText.text.toString().trim()

            if (bindCode.isEmpty()) {
                statusText.text = "错误：绑定码不能为空"
                return@setOnClickListener
            }

            if (!bindCode.startsWith("PAY:")) {
                statusText.text = "错误：绑定码格式错误，应以 PAY: 开头"
                return@setOnClickListener
            }

            if (secretKey.isEmpty()) {
                statusText.text = "错误：密钥不能为空"
                return@setOnClickListener
            }

            // 保存绑定码和密钥
            sharedPreferences.edit()
                .putString("bind_code", bindCode)
                .putString("secret_key", secretKey)
                .apply()
            statusText.text = "状态：已保存 ✅\n绑定码：$bindCode\n密钥：$secretKey"
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        }
    }

    // 处理返回按钮
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}