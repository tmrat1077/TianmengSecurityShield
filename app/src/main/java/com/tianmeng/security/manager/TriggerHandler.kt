package com.tianmeng.security.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tianmeng.security.App
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.service.SecurityAccessibilityService
import com.tianmeng.security.util.RiskyAppScanner
import java.util.concurrent.atomic.AtomicLong

/**
 * 触发处理器：音量键 / 摇一摇 的统一入口。
 *
 * 新的紧急救援链路：
 * 1. 立即循环强制停止全部第三方应用（锁机病毒必然被终结，不依赖特征识别）
 * 2. 回到桌面，脱离锁屏覆盖
 * 3. 弹出悬浮窗「你是否遭遇病毒威胁」，点「是」进入卸载选择页
 *
 * 全过程在后台线程，绝不阻塞主线程。
 */
object TriggerHandler {

    private const val TAG = "TriggerHandler"
    private const val COOLDOWN_MS = 1000L

    private val lastTrigger = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param source "volume" / "shake"
     */
    fun trigger(source: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastTrigger.get()
        if (now - previous < COOLDOWN_MS) return
        if (!lastTrigger.compareAndSet(previous, now)) return

        Log.d(TAG, "Trigger from $source")
        val context = App.instance
        Thread({ runTrigger(context, source) }, "shield-trigger").apply {
            isDaemon = true
        }.start()
    }

    private fun runTrigger(context: Context, source: String) {
        // 0. 先把悬浮窗弹出来：必须在任何扫描 / 分析 / 命令之前，保证秒弹
        try {
            if (EmergencyOverlay.canDraw(context)) {
                EmergencyOverlay.show(source, emptyList())
            } else {
                toast(context.getString(R.string.overlay_not_granted_hint))
                EmergencyOverlay.showFallbackUi(context, emptyList())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Show emergency overlay failed", e)
        }

        // 1. 采集可见窗口并判断可疑应用（在回到桌面之前，窗口信息才准确）
        val suspected = try {
            val candidates = collectCandidates(context)
            val list = candidates.mapNotNull { pkg ->
                if (VirusManager.analyzeApp(context, pkg) != null) pkg else null
            }
            Log.d(TAG, "Candidates=$candidates suspected=$list")
            list
        } catch (e: Throwable) {
            Log.e(TAG, "Analyze candidates failed", e)
            emptyList()
        }
        // 分析完成后回填，点「是」时卸载页可自动勾选这些应用
        EmergencyOverlay.updateSuspected(suspected)

        // 预先扫描卸载页列表，用户点「是」时页面瞬间就有内容
        try {
            RiskyAppScanner.prefetch(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Prefetch risky apps failed", e)
        }

        // 2. 回到桌面 + 震动，尽快脱离锁机覆盖层
        goHome(context)
        vibrate(context, 200)

        // 3. 立即循环强停全部第三方应用，真正解除锁机
        try {
            EmergencyEngine.engage(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Engage emergency engine failed", e)
        }

        // 4. 按当前模式执行冻结 / 卸载（终结模式会冻结并卸载所有恶意病毒）
        try {
            val handled = VirusManager.enforceByMode(context)
            Log.d(TAG, "Mode enforcement handled $handled apps")
        } catch (e: Throwable) {
            Log.e(TAG, "Mode enforcement failed", e)
        }
    }

    /**
     * 摇一摇触发阈值（加速度三轴差值之和），档位越高越难触发、越不易误触
     */
    fun shakeThreshold(): Float = when (Prefs.shakeLevel) {
        0 -> 7f
        1 -> 11f
        2 -> 17f
        else -> 26f
    }

    /**
     * 收集当前可能在前台 / 可见的应用包名（含悬浮窗覆盖的锁机病毒）
     */
    private fun collectCandidates(context: Context): List<String> {
        val result = LinkedHashSet<String>()

        // 1. 无障碍服务读取到的所有可见窗口（最关键：覆盖锁屏的悬浮窗也能拿到）
        try {
            SecurityAccessibilityService.currentWindowPackages().forEach { result.add(it) }
        } catch (e: Throwable) {
            Log.e(TAG, "Read windows failed", e)
        }

        // 2. 无障碍跟踪到的前台包名
        SecurityAccessibilityService.foregroundPackage?.let { result.add(it) }

        // 3. 进程重要性兜底
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
            @Suppress("DEPRECATION")
            am?.getRunningAppProcesses()?.forEach {
                if (it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    result.add(it.processName)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Read processes failed", e)
        }

        val self = context.packageName
        return result.filter {
            it.isNotBlank() &&
                    it != self &&
                    it != "com.android.systemui" &&
                    !it.contains(":") // 过滤子进程
        }
    }

    private fun goHome(context: Context) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Return home failed", e)
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            try {
                android.widget.Toast.makeText(App.instance, message, android.widget.Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun vibrate(context: Context, durationMs: Long) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        durationMs,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Throwable) {
            // ignore
        }
    }
}
