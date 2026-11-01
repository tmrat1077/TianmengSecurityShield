package com.tianmeng.security.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tianmeng.security.R

/**
 * 停用冻结应用被点击时显示的接管提示界面
 */
class FrozenAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frozen_app)

        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "未知应用"

        val tvInfo = findViewById<TextView>(R.id.tvFrozenAppInfo)
        val btnClose = findViewById<Button>(R.id.btnCloseFrozen)

        tvInfo.text = getString(R.string.app_taken_over_msg, appName)

        btnClose.setOnClickListener {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }
}
