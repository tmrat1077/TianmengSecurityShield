package com.tianmeng.security

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tianmeng.security.manager.ShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shell 终端：以 Root / Shizuku 身份执行任意命令，带常用命令快捷入口与历史输出。
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var tvMode: TextView
    private lateinit var etCommand: EditText
    private lateinit var scroll: ScrollView
    private lateinit var rowQuick: LinearLayout

    private val outputBuffer = StringBuilder()
    private val history = mutableListOf<String>()

    private val quickCommands = listOf(
        "id",
        "pm list packages -3",
        "df -h",
        "free -m",
        "dumpsys meminfo | head -30",
        "top -n 1 | head -20",
        "ls /sdcard/DCIM",
        "pm list packages -d"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        tvOutput = findViewById(R.id.tvTerminalOutput)
        tvMode = findViewById(R.id.tvTerminalMode)
        etCommand = findViewById(R.id.etTerminalCommand)
        scroll = findViewById(R.id.scrollTerminal)
        rowQuick = findViewById(R.id.rowQuickCommands)

        findViewById<MaterialToolbar>(R.id.toolbarTerminal).setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnTerminalRun).setOnClickListener {
            runCommand(etCommand.text.toString())
        }

        etCommand.setOnEditorActionListener { _, _, _ ->
            runCommand(etCommand.text.toString())
            true
        }

        findViewById<MaterialButton>(R.id.btnTerminalClear).setOnClickListener {
            outputBuffer.setLength(0)
            renderOutput()
        }

        findViewById<MaterialButton>(R.id.btnTerminalHelp).setOnClickListener {
            appendLine("常用命令：")
            appendLine("  id                      查看当前执行身份")
            appendLine("  pm list packages -3     列出第三方应用")
            appendLine("  pm list packages -d     列出已停用应用")
            appendLine("  pm disable-user --user 0 包名   停用应用")
            appendLine("  pm enable 包名          启用应用")
            appendLine("  am force-stop 包名      强制停止")
            appendLine("  am start -n 包名/.界面  启动应用")
            appendLine("  df -h                   查看存储占用")
            appendLine("  free -m                 查看内存占用")
            appendLine("  ls /sdcard/DCIM         查看相册目录")
            appendLine("  清空数据：pm clear 包名（不可恢复）")
        }

        buildQuickChips()
        refreshMode()
    }

    private fun buildQuickChips() {
        quickCommands.forEach { cmd ->
            val chip = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = cmd
                textSize = 11f
                isAllCaps = false
                setTextColor(ContextCompat.getColor(this@TerminalActivity, R.color.text_secondary))
                setPadding(24, 0, 24, 0)
                minimumWidth = 0
                minimumHeight = 0
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener {
                    etCommand.setText(cmd)
                    runCommand(cmd)
                }
            }
            rowQuick.addView(chip)
        }
    }

    private fun refreshMode() {
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) {
                ShellManager.awaitReady(3000)
                ShellManager.currentMode
            }
            tvMode.text = when (mode) {
                ShellManager.Mode.ROOT -> "当前执行通道：Root（su）"
                ShellManager.Mode.SHIZUKU -> "当前执行通道：Shizuku"
                ShellManager.Mode.NONE -> "当前执行通道：无权限，命令只会以普通应用身份执行"
            }
            appendLine("天梦 Shell 终端已就绪 · 命令以 ${when (mode) {
                ShellManager.Mode.ROOT -> "Root"
                ShellManager.Mode.SHIZUKU -> "Shizuku"
                else -> "普通应用"
            }} 身份执行")
            appendLine("输入 help 或点「常用命令」查看示例。")
            appendLine("")
        }
    }

    private fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        etCommand.setText("")
        history.add(cmd)

        if (cmd == "clear") {
            outputBuffer.setLength(0)
            renderOutput()
            return
        }
        if (cmd == "help") {
            findViewById<MaterialButton>(R.id.btnTerminalHelp).performClick()
            return
        }

        appendLine("$ $cmd")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ShellManager.awaitReady(3000)
                ShellManager.exec(cmd)
            }
            if (result.stdout.isNotBlank()) appendLine(result.stdout.trimEnd())
            if (result.stderr.isNotBlank()) appendLine("[stderr] " + result.stderr.trimEnd())
            when {
                result.exitCode == -1 && result.stderr.contains("No permission", true) -> {
                    appendLine("[错误] 未检测到 Root / Shizuku 权限，请先在首页申请权限。")
                }
                else -> appendLine("[exit=${result.exitCode}]")
            }
            appendLine("")
            renderOutput()
        }
    }

    private fun appendLine(line: String) {
        outputBuffer.append(line).append('\n')
        renderOutput()
    }

    private fun renderOutput() {
        tvOutput.text = outputBuffer.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
