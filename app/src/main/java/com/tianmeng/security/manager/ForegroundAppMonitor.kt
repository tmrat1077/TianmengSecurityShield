package com.tianmeng.security.manager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * 通过「使用情况访问」读取最近的前台应用。
 * 无障碍未开启或事件丢失时，用它兜底识别锁机病毒并触发智能拦截。
 */
object ForegroundAppMonitor {

    private const val TAG = "ForegroundMonitor"
    private const val QUERY_WINDOW_MS = 10_000L

    fun currentForeground(context: Context): String? {
        return try {
            if (!PermissionManager.hasUsageAccess(context)) return null
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - QUERY_WINDOW_MS, now)
            val event = UsageEvents.Event()
            var lastPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED 数值相同
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrBlank()) lastPackage = pkg
                }
            }
            lastPackage?.takeIf {
                it != context.packageName && it != "com.android.systemui"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Read foreground app failed", e)
            null
        }
    }
}
