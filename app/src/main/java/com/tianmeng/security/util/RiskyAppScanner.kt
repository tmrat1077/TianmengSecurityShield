package com.tianmeng.security.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.tianmeng.security.manager.VirusManager

/**
 * 高危应用扫描：列出第三方应用，并标注其是否申请了无障碍 / 悬浮窗 / 设备管理器。
 * 带高危特征的应用排在列表前面，方便紧急情况下优先卸载。
 */
object RiskyAppScanner {

    private const val TAG = "RiskyAppScanner"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cache: List<RiskyApp>? = null

    @Volatile
    private var cachedAt = 0L

    data class RiskyApp(
        val packageName: String,
        val appName: String,
        val hasAccessibility: Boolean,
        val hasOverlay: Boolean,
        val hasAdmin: Boolean,
        val isSystem: Boolean
    ) {
        val signalCount: Int
            get() = listOf(hasAccessibility, hasOverlay, hasAdmin).count { it }
    }

    /**
     * 紧急触发时提前扫描，用户点「是」进入卸载页时直接命中缓存，页面秒开。
     */
    fun prefetch(context: Context) {
        val appContext = context.applicationContext
        Thread({
            try {
                scan(appContext)
            } catch (e: Throwable) {
                Log.e(TAG, "Prefetch failed", e)
            }
        }, "risky-prefetch").apply { isDaemon = true }.start()
    }

    private fun cached(): List<RiskyApp>? {
        val data = cache ?: return null
        return if (SystemClock.elapsedRealtime() - cachedAt <= CACHE_TTL_MS) data else null
    }

    fun scan(context: Context): List<RiskyApp> {
        cached()?.let { return it }
        val pm = context.packageManager
        val self = context.packageName
        val result = mutableListOf<RiskyApp>()

        val installed = try {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (e: Throwable) {
            Log.e(TAG, "List applications failed", e)
            return emptyList()
        }

        for (info in installed) {
            val pkg = info.packageName
            // 保护自身与 Shizuku，避免用户误删后护盾失效
            if (pkg == self || pkg.startsWith("moe.shizuku")) continue
            val flags = info.flags
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            try {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS
                )
                val perms = packageInfo.requestedPermissions?.toList() ?: emptyList()
                val hasAdmin = VirusManager.isActiveDeviceAdmin(context, pkg) ||
                        (packageInfo.receivers?.any {
                            it.permission == "android.permission.BIND_DEVICE_ADMIN"
                        } == true)

                result.add(
                    RiskyApp(
                        packageName = pkg,
                        appName = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrElse { pkg },
                        hasAccessibility = "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms,
                        hasOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms,
                        hasAdmin = hasAdmin,
                        isSystem = isSystem
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Parse $pkg failed", e)
            }
        }

        val sorted = result.sortedWith(
            compareByDescending<RiskyApp> { it.signalCount }
                .thenBy { it.isSystem }
                .thenBy { it.appName }
        )
        cache = sorted
        cachedAt = SystemClock.elapsedRealtime()
        return sorted
    }
}
