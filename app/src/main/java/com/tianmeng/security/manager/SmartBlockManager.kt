package com.tianmeng.security.manager

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tianmeng.security.App
import com.tianmeng.security.MainActivity
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.util.AppUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 智能拦截：安装、打开应用时自动识别真实锁机病毒 / 一键 Root 工具并静默卸载。
 *
 * 前置条件（缺一不可）：
 * - 有 Root：Root + 无障碍 + 悬浮窗
 * - 无 Root：Shizuku 已授权 + 无障碍 + 悬浮窗
 */
object SmartBlockManager {

    private const val TAG = "SmartBlockManager"
    private const val NOTIFICATION_ID = 1004
    private const val FOREGROUND_COOLDOWN_MS = 60_000L
    private const val PERIODIC_SCAN_INTERVAL_MS = 5 * 60_000L

    private var lastPeriodicScanAt = 0L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smart-block").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val recentCheck = ConcurrentHashMap<String, Long>()

    /** 当前缺少的拦截条件，空列表表示全部就绪 */
    fun gateIssues(context: Context): List<String> {
        val issues = mutableListOf<String>()
        val mode = ShellManager.currentMode
        val privileged = when (mode) {
            ShellManager.Mode.ROOT -> true
            ShellManager.Mode.SHIZUKU -> PermissionManager.isShizukuGranted()
            else -> false
        }
        if (!privileged) issues.add("Root 或 Shizuku 授权")
        if (!PermissionManager.isAccessibilityEnabled(context) &&
            !PermissionManager.hasUsageAccess(context)
        ) {
            issues.add("无障碍服务或使用情况访问")
        }
        if (!PermissionManager.isOverlayGranted(context)) issues.add("悬浮窗权限")
        return issues
    }

    fun isGateSatisfied(context: Context): Boolean = gateIssues(context).isEmpty()

    /** 开启时先扫描一遍已安装应用，返回本次自动卸载数量 */
    fun scanNow(context: Context, onDone: (Int) -> Unit) {
        if (!Prefs.smartBlock) {
            onDone(0)
            return
        }
        executor.execute {
            if (!isGateSatisfied(context)) {
                main.post { onDone(0) }
                return@execute
            }
            var removed = 0
            try {
                for (pkg in AppUtils.getThirdPartyPackages(context)) {
                    if (pkg == context.packageName) continue
                    val reason = VirusManager.analyzeForAutoRemove(context, pkg) ?: continue
                    if (VirusManager.removeVirus(context, pkg)) {
                        removed++
                        notifyRemoved(context, pkg, reason)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Smart block scan failed", e)
            }
            main.post { onDone(removed) }
        }
    }

    /** 新安装 / 更新应用时触发 */
    fun onPackageChanged(context: Context, packageName: String) {
        if (!Prefs.smartBlock) return
        executor.execute {
            if (!isGateSatisfied(context)) return@execute
            val reason = VirusManager.analyzeForAutoRemove(context, packageName) ?: return@execute
            val removed = VirusManager.removeVirus(context, packageName)
            if (removed) notifyRemoved(context, packageName, reason)
        }
    }

    /** 应用进入前台时触发（无障碍事件调用），同一应用 60 秒内只检查一次 */
    fun onForegroundPackage(context: Context, packageName: String) {
        if (!Prefs.smartBlock) return
        if (packageName == context.packageName) return
        val now = System.currentTimeMillis()
        val last = recentCheck[packageName]
        if (last != null && now - last < FOREGROUND_COOLDOWN_MS) return
        recentCheck[packageName] = now
        onPackageChanged(context, packageName)
    }

    /** 护盾运行期间定期全量巡检，兜住从不打开、也不会触发前台事件的隐藏病毒 */
    fun periodicScan(context: Context) {
        if (!Prefs.smartBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPeriodicScanAt < PERIODIC_SCAN_INTERVAL_MS) return
        lastPeriodicScanAt = now
        scanNow(context) { removed ->
            if (removed > 0) Log.d(TAG, "Periodic scan removed $removed")
        }
    }

    fun clearCooldown() {
        recentCheck.clear()
    }

    private fun notifyRemoved(context: Context, pkg: String, reason: String) {
        try {
            val name = AppUtils.getAppName(context, pkg)
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                context, 1004, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, App.CHANNEL_ID_ALERT)
                .setSmallIcon(R.drawable.ic_shield_check)
                .setContentTitle(context.getString(R.string.smart_block_removed_title))
                .setContentText(context.getString(R.string.smart_block_removed_text, name, reason))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.smart_block_removed_text, name, reason)
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "Notify removed failed", e)
        }
    }
}
