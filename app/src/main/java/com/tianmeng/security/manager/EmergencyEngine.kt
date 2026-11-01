package com.tianmeng.security.manager

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.tianmeng.security.util.AppUtils
import com.tianmeng.security.util.CommandObfuscator

/**
 * 紧急终止引擎：触发后循环强制停止全部第三方应用。
 *
 * 锁机病毒无论是否被特征识别出来，只要它运行在第三方应用中，
 * 持续 force-stop 就能把它连同其覆盖窗口一起终结，
 * 从而真正解除锁机界面（而不是"看起来拦截了"）。
 */
object EmergencyEngine {

    private const val TAG = "EmergencyEngine"
    private const val LOOP_INTERVAL_MS = 1100L
    private const val MAX_ROUNDS = 24

    /** 每条 shell 命令最多终止的应用数量，避免单条命令被安全软件整体拦截后全部失效 */
    private const val CHUNK_SIZE = 6

    /**
     * 其他安全类工具（游龙）会监控命令内容：
     * 一旦命令里出现它自己的包名关键字（如 youlong），就会把整条命令拦下。
     * 因此这些包不参与批量终止，既避免被拦截，也避免与同类安全软件互相攻击。
     */
    private val PROTECTED_KEYWORDS = listOf("youlong", "youloong", "youlo")

    @Volatile
    private var active = false

    @Volatile
    var killedCount: Int = 0
        private set

    fun engage(context: Context) {
        if (active) return
        active = true
        killedCount = 0
        val appContext = context.applicationContext
        Thread({ loop(appContext) }, "emergency-terminator").apply {
            isDaemon = true
            start()
        }
    }

    fun disengage() {
        active = false
    }

    val isActive: Boolean get() = active

    private fun loop(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java)
        var rounds = 0

        while (active && rounds < MAX_ROUNDS) {
            rounds++
            try {
                val packages = AppUtils.getThirdPartyPackages(context).filter {
                    it != context.packageName &&
                            !it.startsWith("moe.shizuku") &&
                            !isProtected(it) &&
                            !VirusManager.isWhitelisted(it)
                }
                if (packages.isNotEmpty()) {
                    var succeeded = 0
                    packages.chunked(CHUNK_SIZE).forEach { chunk ->
                        val script = CommandObfuscator.forceStopAll(chunk)
                        val result = ShellManager.exec(script)
                        if (result.isSuccess) {
                            succeeded += chunk.size
                        } else {
                            // 本片被拦截或无 Shizuku / Root：退化为系统级后台清理，不影响其他分片
                            chunk.forEach { am?.killBackgroundProcesses(it) }
                            Log.w(TAG, "Chunk blocked, fallback to killBackgroundProcesses: ${chunk.size} apps")
                        }
                    }
                    killedCount = packages.size
                    Log.d(TAG, "Round $rounds terminated $succeeded/${packages.size} apps")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Terminate round failed", e)
            }
            try {
                Thread.sleep(LOOP_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
        active = false
    }

    private fun isProtected(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return PROTECTED_KEYWORDS.any { pkg.contains(it) }
    }
}
