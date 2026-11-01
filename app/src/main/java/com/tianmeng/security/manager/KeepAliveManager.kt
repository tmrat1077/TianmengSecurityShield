package com.tianmeng.security.manager

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.service.SecurityShieldService

/**
 * 防冻结 / 后台加固：
 * - 前台服务常驻，划掉后台后自动重启
 * - 使用情况访问用于识别前台锁机病毒
 * - 加入电池优化白名单，避免被系统后台限制
 */
object KeepAliveManager {

    private const val TAG = "KeepAliveManager"

    fun isShieldRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == SecurityShieldService::class.java.name
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 当前缺少的加固条件，空列表表示完全就绪 */
    fun missingItems(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!PermissionManager.hasUsageAccess(context)) missing.add("使用情况访问")
        if (!PermissionManager.isIgnoringBatteryOptimizations(context)) missing.add("电池优化白名单")
        if (!isShieldRunning(context)) missing.add("护盾后台服务")
        return missing
    }

    /** 开启加固：记录开关、启动护盾、安排看门狗 */
    fun enable(context: Context) {
        Prefs.keepAlive = true
        Prefs.shieldEnabled = true
        try {
            ShieldWatchdog.arm(context)
            SecurityShieldService.start(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Enable keep alive failed", e)
        }
    }

    fun disable(context: Context) {
        Prefs.keepAlive = false
        // 用户仍开启护盾时保留看门狗，只是不再视为「防冻结」加固
        if (!Prefs.shieldEnabled) {
            try {
                ShieldWatchdog.disarm(context)
            } catch (e: Throwable) {
                Log.w(TAG, "Disarm watchdog failed", e)
            }
        }
    }
}
