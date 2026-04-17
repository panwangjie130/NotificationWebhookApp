package com.example.notificationwebhookapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("NotificationWebhookPrefs", Context.MODE_PRIVATE)

        val titleText = findViewById<TextView>(R.id.titleText)
        val bindCodeEditText = findViewById<EditText>(R.id.bindCodeEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        // 设置标题
        titleText.text = "收款通知转发配置"

        // 加载保存的绑定码
        bindCodeEditText.setText(sharedPreferences.getString("bind_code", ""))

        // 检查配置状态
        val bindCode = sharedPreferences.getString("bind_code", "")
        if (bindCode.isNullOrEmpty()) {
            statusText.text = "状态：未配置"
        } else {
            statusText.text = "状态：已配置 (${bindCode})"
        }

        saveButton.setOnClickListener {
            val bindCode = bindCodeEditText.text.toString().trim()

            if (bindCode.isEmpty()) {
                statusText.text = "错误：绑定码不能为空"
                return@setOnClickListener
            }

            if (!bindCode.startsWith("PAY:")) {
                statusText.text = "错误：绑定码格式错误，应以 PAY: 开头"
                return@setOnClickListener
            }

            // 保存绑定码
            sharedPreferences.edit().putString("bind_code", bindCode).apply()
            statusText.text = "状态：已保存 (${bindCode})"
        }
    }
}
