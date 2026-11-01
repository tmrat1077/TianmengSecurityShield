package com.tianmeng.security.manager

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import rikka.shizuku.Shizuku

/**
 * 权限管理器：Shizuku、Root、无障碍服务
 */
object PermissionManager {

    private const val TAG = "PermissionManager"
    private const val SHIZUKU_PERMISSION_CODE = 1001

    /**
     * 检查 Shizuku 是否已授权
     */
    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 检查 Shizuku 是否可用（服务运行中）
     */
    fun isShizukuAvailable(): Boolean {
        return ShellManager.isShizukuAvailable()
    }

    /**
     * 检查是否有 Root
     */
    fun isRootAvailable(): Boolean {
        return ShellManager.isRootAvailable()
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission(activity: Activity) {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Request Shizuku failed", e)
        }
    }

    /**
     * 检查悬浮窗权限。
     * 部分 ROM（MIUI / ColorOS 等）的 Settings.canDrawOverlays 存在缓存或返回 false 的情况，
     * 这里再查一次 AppOps，避免权限已开启却显示「未授权」。
     */
    fun isOverlayGranted(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(context)) return true
        return try {
            val ops = context.getSystemService(android.app.AppOpsManager::class.java)
            val mode = ops?.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(),
                context.packageName
            ) ?: -1
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            Log.w(TAG, "Check overlay via appops failed", e)
            false
        }
    }

    /**
     * 检查无障碍服务是否已启用
     * 同时看系统安全设置里的服务列表与 AccessibilityManager，
     * 避免某些 ROM 已开启但仍返回空列表导致显示「未开启」。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val expected = "${context.packageName}/${com.tianmeng.security.service.SecurityAccessibilityService::class.java.name}"
            val configured = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val inSettings = configured.split(':').any { item ->
                item.equals(expected, ignoreCase = true) ||
                        item.startsWith("${context.packageName}/")
            }
            if (inSettings) return true

            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            if (!am.isEnabled) return false
            val lists = listOf(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                0
            )
            lists.any { feedback ->
                am.getEnabledAccessibilityServiceList(feedback).any {
                    it.resolveInfo?.serviceInfo?.packageName == context.packageName
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Check accessibility failed", e)
            false
        }
    }

    /**
     * 检查「使用情况访问」权限。
     * 用于读取前台应用，兼容无障碍未开启的场景，识别到锁机病毒直接拦截。
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(android.app.AppOpsManager::class.java)
                ?: return false
            val mode = ops.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            Log.w(TAG, "Check usage access failed", e)
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Open usage access settings failed", e)
        }
    }

    /** 是否已加入电池优化白名单（允许后台高耗电） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (e: Throwable) {
            false
        }
    }

    /** 申请忽略电池优化（系统会弹「允许后台高耗电」确认框） */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Throwable) {
                Log.e(TAG, "Request ignore battery optimization failed", e2)
            }
        }
    }

    /**
     * 打开无障碍服务设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open accessibility settings failed", e)
        }
    }

    /**
     * 打开 Shizuku 应用（如果已安装）
     */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("market://details?id=moe.shizuku.privileged.api")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否具备执行特权操作的能力（Shizuku 或 Root）
     */
    fun hasPrivilegedPermission(): Boolean {
        return ShellManager.currentMode != ShellManager.Mode.NONE
    }

    /**
     * 重新探测 Shell 模式（后台执行，不阻塞界面）
     */
    fun refreshShellMode() {
        ShellManager.init()
    }
}
