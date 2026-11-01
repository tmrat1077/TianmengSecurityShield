package com.tianmeng.security.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tianmeng.security.R
import com.tianmeng.security.manager.VirusManager
import com.tianmeng.security.util.AppUtils
import com.tianmeng.security.util.LockScreenUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 病毒拦截界面：显示检测到的病毒并执行清除
 */
class VirusBlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_CLEANED = "extra_cleaned"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_virus_block)
        LockScreenUi.makeFullScreen(this)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        val alreadyCleaned = intent.getBooleanExtra(EXTRA_CLEANED, false)

        val appName = AppUtils.getAppName(this, packageName)
        val tvInfo = findViewById<TextView>(R.id.tvVirusInfo)
        val tvStatus = findViewById<TextView>(R.id.tvCleanStatus)
        val progress = findViewById<android.widget.ProgressBar>(R.id.pbCleaning)

        tvInfo.text = if (reason.isNotBlank()) {
            getString(R.string.virus_block_msg, appName, packageName) + "\n判定依据：" + reason
        } else {
            getString(R.string.virus_block_msg, appName, packageName)
        }

        if (alreadyCleaned) {
            // TriggerHandler 已在后台完成强制退出 + 清除，这里只展示结果
            progress.visibility = android.view.View.GONE
            tvStatus.text = getString(R.string.virus_cleaned_msg, appName)
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
            return
        }

        // 延迟一点显示清除过程
        Handler(Looper.getMainLooper()).postDelayed({
            tvStatus.text = "正在强制退出并清除…"
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    VirusManager.removeVirus(this@VirusBlockActivity, packageName)
                }
                if (success) {
                    tvStatus.text = getString(R.string.virus_cleaned_msg, appName)
                } else {
                    tvStatus.text = "清除失败，请检查权限"
                }
                // 2秒后自动关闭
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 2000)
            }
        }, 500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LockScreenUi.hideSystemBars(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 禁止返回，等待清除完成
    }
}
