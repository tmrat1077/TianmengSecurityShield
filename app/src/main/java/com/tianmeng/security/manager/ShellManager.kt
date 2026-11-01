package com.tianmeng.security.manager

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.tianmeng.security.util.CommandObfuscator
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 命令执行管理器：支持 Shizuku 和 Root 两种模式
 *
 * 注意：Root/Shizuku 探测一定要放在后台线程，并且必须带超时。
 * 直接在主线程 exec("su") 时，若 Magisk 弹出授权框，主线程会被永久阻塞，
 * 系统判定 ANR 后直接杀掉进程（用户感知就是"闪退"）。
 */
object ShellManager {

    private const val TAG = "ShellManager"
    private const val DETECT_TIMEOUT_SECONDS = 3L

    enum class Mode { NONE, SHIZUKU, ROOT }

    @Volatile
    var currentMode: Mode = Mode.NONE
        private set

    /** 是否检测到游龙这类会拦截命令 / 要求自身授权的第三方安全工具 */
    @Volatile
    var guardInterceptorPresent: Boolean = false
        private set

    private val latch = CountDownLatch(1)
    private val detectionStarted = AtomicBoolean(false)

    /**
     * 启动后台探测（非阻塞，可在 Application.onCreate 中安全调用）
     */
    fun init() {
        if (!detectionStarted.compareAndSet(false, true)) return
        Thread({
            val hasRoot = isRootAvailable()
            val hasGuard = GuardDetector.hasYoulong()
            guardInterceptorPresent = hasGuard
            val mode = try {
                // 优先 Root：直接走 su，不经过 Shizuku，也不会经过第三方安全软件的授权弹窗。
                // 没有 Root 时一律使用 Shizuku（含无线调试启动的 Shizuku），不再跳过授权。
                when {
                    hasRoot -> Mode.ROOT
                    isShizukuAvailable() -> Mode.SHIZUKU
                    else -> Mode.NONE
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Detect shell mode failed", e)
                Mode.NONE
            }
            currentMode = mode
            latch.countDown()
            Log.d(TAG, "Shell mode: $mode")
        }, "shell-detect").apply { isDaemon = true }.start()
    }

    /**
     * 同步探测一次执行通道（必须放在后台线程），并把结果写回 currentMode。
     * 用于界面刷新，避免 redetect 异步还没结束时界面读到旧状态。
     */
    fun probeMode(): Mode {
        val mode = try {
            when {
                isRootAvailable() -> Mode.ROOT
                isShizukuAvailable() -> Mode.SHIZUKU
                else -> Mode.NONE
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Probe mode failed", e)
            Mode.NONE
        }
        currentMode = mode
        try {
            guardInterceptorPresent = GuardDetector.hasYoulong()
        } catch (e: Throwable) {
            // ignore
        }
        return mode
    }

    /**
     * 重新探测（授权状态变化后调用，例如刚在无线调试页面完成 Shizuku 授权）
     */
    fun redetect() {
        Thread({
            try {
                guardInterceptorPresent = GuardDetector.hasYoulong()
                currentMode = when {
                    isRootAvailable() -> Mode.ROOT
                    isShizukuAvailable() -> Mode.SHIZUKU
                    else -> Mode.NONE
                }
                Log.d(TAG, "Shell mode redetected: $currentMode")
            } catch (e: Throwable) {
                Log.e(TAG, "Redetect shell mode failed", e)
            }
        }, "shell-redetect").apply { isDaemon = true }.start()
    }

    /**
     * 等待探测结果（只在真正要执行命令时调用，勿在主线程等待）
     */
    fun awaitReady(timeoutMs: Long = 2000) {
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Root 探测：带超时，避免 Magisk 授权框导致阻塞
     */
    fun isRootAvailable(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            try {
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("id\n")
                os.writeBytes("exit\n")
                os.flush()
                os.close()
            } catch (e: Exception) {
                Log.w(TAG, "Write su command failed: ${e.message}")
            }
            val finished = process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * 执行命令并返回结果
     */
    fun exec(command: String): CommandResult {
        awaitReady()
        return when (currentMode) {
            Mode.ROOT -> {
                val result = execRoot(command)
                // Root 被拒绝 / su 授权失败时，才回退到 Shizuku
                if (isDenied(result)) execShizukuIfAvailable(command) ?: result else result
            }
            Mode.SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    currentMode = Mode.NONE
                    CommandResult(-1, "", "Shizuku 服务未运行")
                } else {
                    execShizuku(command)
                }
            }
            Mode.NONE -> CommandResult(-1, "", "No permission mode available")
        }
    }

    /**
     * 批量执行命令
     */
    fun exec(commands: List<String>): CommandResult {
        return exec(commands.joinToString("\n"))
    }

    private fun isDenied(result: CommandResult): Boolean {
        if (result.exitCode == -1) return true
        val err = result.stderr.lowercase()
        return err.contains("permission denied") || err.contains("not permitted") ||
                err.contains("not granted") || err.startsWith("su:")
    }

    private fun execShizukuIfAvailable(command: String): CommandResult? {
        return try {
            if (isShizukuAvailable()) execShizuku(command) else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun execShizuku(command: String): CommandResult {
        return try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,
                null
            )
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku exec failed: ${e.message}")
            CommandResult(-1, "", e.message ?: "Shizuku error")
        }
    }

    private fun execRoot(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Root exec failed: ${e.message}")
            CommandResult(-1, "", e.message ?: "Root error")
        }
    }

    // ============ 应用操作 ============

    /** 强制停止应用（混淆命令，避免被同类安全软件拦截） */
    fun forceStop(packageName: String): CommandResult {
        return exec(CommandObfuscator.forceStop(packageName))
    }

    /** 清除应用数据 */
    fun clearAppData(packageName: String): CommandResult {
        return exec(CommandObfuscator.clearData(packageName))
    }

    /** 卸载应用（用户应用） */
    fun uninstallApp(packageName: String): CommandResult {
        return exec(CommandObfuscator.uninstallUser(packageName))
    }

    /** 卸载应用（常规卸载） */
    fun uninstallPlain(packageName: String): CommandResult {
        return exec(CommandObfuscator.uninstall(packageName))
    }

    /** 停用应用（停用冻结，图标保留但无法启动） */
    fun disableApp(packageName: String): CommandResult {
        return exec(CommandObfuscator.disable(packageName))
    }

    /** 停用并隐藏应用（常规冻结，图标消失） */
    fun disableAppAndHide(packageName: String): CommandResult {
        return exec(CommandObfuscator.disableUser(packageName))
    }

    /** 启用应用 */
    fun enableApp(packageName: String): CommandResult {
        return exec(CommandObfuscator.enable(packageName))
    }

    /** 检查应用是否被禁用 */
    fun isAppDisabled(packageName: String): Boolean {
        val result = exec("pm list packages -d | grep $packageName")
        return result.stdout.contains(packageName)
    }

    /** 获取应用的启动 Activity */
    fun getLauncherActivity(packageName: String): String {
        val result = exec("cmd package resolve-activity --brief $packageName | tail -n 1")
        return result.stdout.trim()
    }

    /** 启动前台服务（供触发场景使用，避免后台启动限制） */
    fun startForegroundService(context: Context, clazz: Class<*>, action: String? = null) {
        val intent = android.content.Intent(context, clazz).apply {
            if (action != null) this.action = action
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}
