package com.tianmeng.security

import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tianmeng.security.manager.FeedbackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 意见反馈：选择类型、填写内容后提交到反馈接口。
 */
class FeedbackActivity : AppCompatActivity() {

    companion object {
        /** 反馈账号固定，不允许用户修改 */
        private const val FEEDBACK_USER = "541881452"
    }

    private lateinit var rgType: RadioGroup
    private lateinit var etContent: EditText
    private lateinit var etUser: EditText
    private lateinit var btnSubmit: MaterialButton
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        rgType = findViewById(R.id.rgFeedbackType)
        etContent = findViewById(R.id.etFeedbackContent)
        etUser = findViewById(R.id.etFeedbackUser)
        btnSubmit = findViewById(R.id.btnFeedbackSubmit)
        tvStatus = findViewById(R.id.tvFeedbackStatus)

        findViewById<MaterialToolbar>(R.id.toolbarFeedback).setNavigationOnClickListener { finish() }

        // 反馈账号固定，不允许修改
        etUser.setText(FEEDBACK_USER)
        etUser.isEnabled = false
        etUser.isFocusable = false
        etUser.isClickable = false

        btnSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        val type = when (rgType.checkedRadioButtonId) {
            R.id.rbFeedbackBug -> "闪退 / 卡顿"
            R.id.rbFeedbackVirus -> "病毒误报"
            R.id.rbFeedbackOther -> "其他问题"
            else -> "功能建议"
        }
        val content = etContent.text.toString().trim()
        val user = etUser.text.toString().trim()
        if (user.isEmpty()) {
            showStatus("请填写用户账号", 2)
            return
        }
        if (content.length < 5) {
            showStatus("请至少输入 5 个字的反馈内容", 2)
            return
        }

        btnSubmit.isEnabled = false
        showStatus("正在提交…", 0)
        lifecycleScope.launch {
            val payload = buildPayload(type, content)
            val result = withContext(Dispatchers.IO) {
                FeedbackManager.submit(user, payload)
            }
            btnSubmit.isEnabled = true
            if (result.isSuccess) {
                etContent.setText("")
                showStatus("提交成功，感谢您的反馈！", 1)
                Toast.makeText(this@FeedbackActivity, "反馈已提交", Toast.LENGTH_SHORT).show()
            } else if (result.msg.contains("账号不存在")) {
                showStatus("反馈账号未在后台登记，请联系管理员处理", 2)
            } else {
                showStatus("提交失败：${result.msg}", 2)
            }
        }
    }

    private fun buildPayload(type: String, content: String): String {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Throwable) {
            ""
        }
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
        return "【$type】\n$content\n\n---\n设备：$device\n版本：v$versionName"
    }

    /**
     * @param kind 0 普通 / 1 成功 / 2 失败
     */
    private fun showStatus(message: String, kind: Int) {
        tvStatus.text = message
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (kind) {
                    1 -> R.color.success
                    2 -> R.color.danger
                    else -> R.color.text_secondary
                }
            )
        )
    }
}
