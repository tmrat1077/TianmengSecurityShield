package com.tianmeng.security.manager

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.tianmeng.security.data.Prefs

/**
 * 格机防护（仅 Root 可用）
 *
 * 锁机软件常见的破坏手段：
 * 1. rm -rf 删除 /data、/sdcard、相册目录
 * 2. dd / mkfs / format 写入分区
 * 3. 炸内存：无限 fork 进程、占满内存
 *
 * 本模块以 Root 身份周期性检查系统进程与内存、相册文件数，
 * 一旦发现破坏行为立即 kill 危险进程并触发紧急救援（强停全部第三方应用）。
 */
object RootGuard {

    private const val TAG = "RootGuard"
    private const val INTERVAL_MS = 2500L
    private const val ALERT_COOLDOWN_MS = 8000L
    private const val MAX_PROCESS_COUNT = 1500

    @Volatile
    private var running = false

    @Volatile
    private var lastAlertAt = 0L

    private var thread: Thread? = null
    private var lastGalleryCount = -1

    /** 破坏性命令特征（全部小写匹配） */
    private val DANGEROUS_TOKENS = listOf(
        "rm -rf /data",
        "rm -rf /sdcard",
        "rm -rf /storage",
        "rm -rf /system",
        "rm -rf /vendor",
        "rm -rf /dev",
        "rm -rf /bootstrap",
        "mkfs.",
        "dd if=/dev/zero",
        "dd of=/data",
        "dd of=/system",
        "dd of=/sdcard",
        "dd of=/storage",
        "shred ",
        "wipe /data",
        "wipe /sdcard",
        "format /data",
        "format /sdcard",
        "/dev/block/by-name"
    )

    fun start(context: Context) {
        if (running) return
        running = true
        lastGalleryCount = -1
        thread = Thread({ loop(context.applicationContext) }, "root-guard").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "Root guard requested")
    }

    fun stop() {
        running = false
        thread = null
        Log.d(TAG, "Root guard stopped")
    }

    private fun loop(context: Context) {
        var tick = 0
        while (running) {
            try {
                tick++
                // 炸内存只需要系统 API，无需 Root
                if (tick % 2 == 0) checkMemory(context)

                // 格机文件 / 相册批量删除需要 Root 才能在破坏发生前 kill 掉执行进程
                if (ShellManager.currentMode == ShellManager.Mode.ROOT) {
                    checkDangerousProcesses(context)
                    if (tick % 8 == 0) checkGallery(context)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Guard round failed", e)
            }
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * 扫描正在执行的破坏性命令，直接 kill 掉执行进程（在文件被删除前打断）。
     */
    private fun checkDangerousProcesses(context: Context) {
        val result = ShellManager.exec("ps -A 2>/dev/null || ps")
        if (result.exitCode != 0 && result.stdout.isEmpty()) return

        val ownUidLine = context.packageName
        val hits = mutableListOf<String>()

        result.stdout.lineSequence().forEach { line ->
            val lower = line.lowercase()
            if (lower.contains(ownUidLine)) return@forEach
            if (!DANGEROUS_TOKENS.any { lower.contains(it) }) return@forEach
            val pid = line.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: return@forEach
            if (pid <= 1) return@forEach
            hits.add(pid.toString())
        }

        if (hits.isEmpty()) return
        // 中断破坏进程：先冻结，再强杀，尽量减小已造成的破坏
        val pids = hits.joinToString(" ")
        ShellManager.exec("kill -STOP $pids 2>/dev/null; kill -9 $pids 2>/dev/null; true")
        Log.w(TAG, "Killed destructive processes: $pids")
        alert(context, "检测到格机 / 删除文件的破坏命令，已强制中止")
    }

    /**
     * 炸内存检测：不需要 Root，直接读系统内存信息与进程数量
     */
    private fun checkMemory(context: Context) {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val processCount = try {
            am.runningAppProcesses?.size ?: 0
        } catch (e: Throwable) {
            0
        }

        val totalMb = mi.totalMem / (1024 * 1024)
        val availableMb = mi.availMem / (1024 * 1024)
        // 可用内存低于 300MB，或低于总内存的 8%，或系统已标记低内存，判定炸内存
        val lowMemory = availableMb < 300L ||
                (totalMb > 0 && mi.availMem * 100 / mi.totalMem < 8) ||
                mi.lowMemory ||
                processCount > MAX_PROCESS_COUNT

        if (lowMemory) {
            Log.w(TAG, "Memory bomb suspected: avail=${availableMb}MB/${totalMb}MB procs=$processCount")
            alert(context, "检测到内存被大量占用（疑似炸内存），已强停全部第三方应用")
        }
    }

    /**
     * 相册保护：文件数短时间内大幅减少，说明正在被批量删除
     */
    private fun checkGallery(context: Context) {
        val countResult = ShellManager.exec(
            "find /sdcard/DCIM /sdcard/Pictures /storage/emulated/0/DCIM -type f 2>/dev/null | wc -l"
        )
        val count = countResult.stdout.trim().toIntOrNull() ?: return
        if (count <= 0) return

        val last = lastGalleryCount
        lastGalleryCount = count

        if (last <= 30) return
        val drop = last - count
        if (drop > maxOf(20, last / 4)) {
            Log.w(TAG, "Gallery shrinking: $last -> $count")
            alert(context, "检测到相册文件被批量删除（$last → $count），已强制中止")
        }
    }

    /**
     * 触发紧急救援：震动 + 循环强停全部第三方应用 + 悬浮窗确认
     */
    private fun alert(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return
        lastAlertAt = now

        Log.w(TAG, "Threat: $reason")
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(400)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Vibrate failed", e)
        }

        EmergencyEngine.engage(context)
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                EmergencyOverlay.show("root_guard", emptyList())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Show guard overlay failed", e)
        }
    }
}
