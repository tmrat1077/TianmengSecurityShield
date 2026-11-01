package com.tianmeng.security.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.tianmeng.security.data.AppInfo
import com.tianmeng.security.data.FreezeMode
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 锁机病毒管理器：扫描、检测、清除
 *
 * 锁机病毒的典型特征：
 * 1. 申请 BIND_DEVICE_ADMIN（设备管理器），防止用户卸载
 * 2. 申请 SYSTEM_ALERT_WINDOW（悬浮窗），覆盖锁屏界面
 * 3. 申请 DISABLE_KEYGUARD（禁用锁屏）
 * 4. 同时具备多项敏感权限的非系统应用
 */
object VirusManager {

    private const val TAG = "VirusManager"

    // 系统白名单包名前缀（防止误删）
    private val SYSTEM_WHITELIST_PREFIXES = listOf(
        "com.android.",
        "android.",
        "com.google.android.",
        "com.miui.",
        "com.huawei.",
        "com.coloros.",
        "com.vivo.",
        "com.oppo.",
        "com.samsung.",
        "com.meizu.",
        "com.qihoo.",
        "com.tencent.mm",
        "com.tencent.mobileqq"
    )

    // 安全应用白名单
    private val SAFE_PACKAGES = setOf(
        "com.tianmeng.security"
    )

    /**
     * 扫描所有应用，标记可疑锁机病毒
     */
    suspend fun scanForViruses(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val apps = AppUtils.getInstalledApps(context)
        apps.mapNotNull { app ->
            val reason = analyzeApp(context, app.packageName)
            if (reason != null) {
                app.copy(isVirus = true, virusReason = reason)
            } else {
                null
            }
        }
    }

    /**
     * 分析单个应用是否为锁机病毒
     * @param mode 拦截模式（0 基础 / 1 强力 / 2 Super Shield / 3 终结）
     * @return 病毒特征描述，null 表示安全
     */
    fun analyzeApp(
        context: Context,
        packageName: String,
        mode: Int = Prefs.blockMode,
        strictOnly: Boolean = false
    ): String? {
        // 白名单 / 系统应用一律不判定为病毒，避免误报
        if (isWhitelisted(packageName)) return null
        if (isSystemApp(context, packageName)) return null

        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val receiversFlag = PackageManager.GET_RECEIVERS
            val flags = PackageManager.GET_PERMISSIONS or receiversFlag
            val packageInfo = pm.getPackageInfo(packageName, flags)

            val perms = packageInfo.requestedPermissions?.toList() ?: emptyList()
            val hasOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms
            val hasKeyguard = "android.permission.DISABLE_KEYGUARD" in perms
            val hasAccessibility = "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms
            val hasInstall = "android.permission.REQUEST_INSTALL_PACKAGES" in perms
            val hasBoot = "android.permission.RECEIVE_BOOT_COMPLETED" in perms
            val hasCamera = "android.permission.CAMERA" in perms

            // 设备管理器：已激活 或 在清单里声明了 DEVICE_ADMIN 接收器
            val declaresAdmin = try {
                packageInfo.receivers?.any {
                    it.permission == "android.permission.BIND_DEVICE_ADMIN"
                } == true
            } catch (e: Exception) {
                false
            }
            val hasDeviceAdmin = declaresAdmin || isActiveDeviceAdmin(context, packageName)

            // 无桌面入口 + 非应用商店安装，是锁机病毒最典型的隐藏方式
            val hasLauncher = hasLauncherEntry(context, packageName)
            val sideLoaded = isSideLoaded(context, packageName)
            val hidden = !hasLauncher

            // 综合风险分：只有高分组合才判定，杜绝“无障碍应用 = 病毒”的误报
            var score = 0
            if (hasDeviceAdmin) score += 4
            if (hasOverlay) score += 2
            if (hasKeyguard) score += 2
            if (hasAccessibility) score += 1
            if (hasBoot) score += 1
            if (hasInstall) score += 1
            if (hasCamera) score += 1
            if (hidden) score += 1
            if (sideLoaded) score += 1

            // 真正的锁机病毒特征组合（强证据，任何模式都成立）
            val lockScreenTypical =
                (hasDeviceAdmin && hasOverlay) ||
                (hasDeviceAdmin && hasKeyguard) ||
                (hasOverlay && hasKeyguard) ||
                (hasOverlay && hasAccessibility && hidden && sideLoaded) ||
                (hasOverlay && hasAccessibility && hasBoot && hasInstall) ||
                (hasOverlay && hasAccessibility && sideLoaded && hasBoot) ||
                (hasOverlay && hasAccessibility && sideLoaded && hasInstall) ||
                (hasOverlay && hasAccessibility && hasCamera && sideLoaded && hasBoot) ||
                (hasDeviceAdmin && hidden && sideLoaded && hasBoot)

            if (lockScreenTypical) {
                val reason = when {
                    hasDeviceAdmin && hasOverlay -> "设备管理器 + 悬浮窗（锁机病毒典型特征）"
                    hasDeviceAdmin && hasKeyguard -> "设备管理器 + 禁用锁屏（锁机病毒典型特征）"
                    hasOverlay && hasKeyguard -> "悬浮窗 + 禁用锁屏（锁屏覆盖型病毒）"
                    hasOverlay && hasAccessibility && hidden && sideLoaded ->
                        "悬浮窗 + 无障碍 + 无桌面图标 + 非商店安装（锁机病毒典型特征）"
                    hasOverlay && hasAccessibility && hasCamera && sideLoaded && hasBoot ->
                        "悬浮窗 + 无障碍 + 相机闪光 + 开机自启（闪光锁机病毒）"
                    hasOverlay && hasAccessibility && sideLoaded && (hasBoot || hasInstall) ->
                        "悬浮窗 + 无障碍 + 开机自启/安装应用（侧载锁机病毒）"
                    hasDeviceAdmin && hidden && sideLoaded && hasBoot ->
                        "设备管理器 + 无桌面图标 + 开机自启（隐藏型锁机病毒）"
                    else -> "悬浮窗 + 无障碍 + 开机自启 + 安装应用（高风险锁机特征）"
                }
                return reason
            }

            // 严格模式（终结模式卸载）只认上面的锁机强证据组合
            if (strictOnly) return null

            // 其余情况仅在激进模式下按高分阈值判定，且必须具备悬浮窗能力
            val aggressiveHit = when (mode) {
                0 -> false
                1 -> hasOverlay && score >= 8
                else -> hasOverlay && hasAccessibility && score >= 8
            }
            if (aggressiveHit) {
                return "命中 $score 项锁机高危特征"
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Analyze app failed: $packageName", e)
            null
        }
    }

    /** 系统应用（含预装）不参与病毒判定 */
    private fun isSystemApp(context: Context, packageName: String): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: Throwable) {
            true
        }
    }

    /** 是否有桌面启动入口 */
    private fun hasLauncherEntry(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Throwable) {
            true
        }
    }

    /** 已知应用商店包名；除这些之外一律视为侧载 / 未知来源安装 */
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.google.android.feedback",
        "com.huawei.appmarket",
        "com.xiaomi.market",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.vivo.appstore",
        "com.sec.android.app.samsungapps",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.baidu.appsearch"
    )

    /** 是否非应用商店安装（侧载 / 未知来源 / 系统安装器） */
    private fun isSideLoaded(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
            // 安装器为空、系统安装器、adb、或不在已知商店列表，都按侧载处理
            installer.isNullOrBlank() || installer !in STORE_INSTALLERS
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 按当前拦截模式执行处置动作（触发紧急救援时调用）：
     * 0 基础：只强制清空后台（强停全部第三方应用），不冻结、不卸载
     * 1 强力：强停 + 强制冻结命中的应用
     * 3 终结：只对确认的锁机病毒强停 + 自动卸载（系统应用仅强冻结）
     *
     * @return 处置的应用数量
     */
    fun enforceByMode(context: Context): Int {
        var mode = Prefs.blockMode.let { if (it == 2) 1 else it }
        // 强力 / 终结模式必须依赖实时守护，护盾未运行时只做基础清后台
        if (mode != 0 && !isGuardRunning(context)) {
            Log.d(TAG, "Guard not running, downgrade mode $mode to basic")
            mode = 0
        }
        val whitelist = Prefs.getWhitelist()
        var handled = 0
        try {
            // 基础模式：只强制清空后台
            if (mode == 0) {
                for (pkg in AppUtils.getThirdPartyPackages(context)) {
                    if (pkg == context.packageName || whitelist.contains(pkg)) continue
                    ShellManager.forceStop(pkg)
                    handled++
                }
                Log.d(TAG, "Basic mode cleared $handled background apps")
                return handled
            }

            val apps = AppUtils.getInstalledApps(context)
            for (app in apps) {
                val pkg = app.packageName
                if (pkg == context.packageName || whitelist.contains(pkg)) continue
                // 终结模式只认真正的锁机强证据，避免误删无障碍类正常应用
                val reason = if (mode >= 3) {
                    analyzeApp(context, pkg, 2, strictOnly = true)
                } else {
                    analyzeApp(context, pkg, mode)
                } ?: continue

                ShellManager.forceStop(pkg)
                if (mode >= 3) {
                    if (app.isSystemApp) {
                        // 系统应用无法卸载，改为强制冻结
                        FreezeManager.freezeApp(context, pkg, FreezeMode.NORMAL)
                    } else {
                        removeVirus(context, pkg)
                    }
                } else {
                    FreezeManager.freezeApp(context, pkg, FreezeMode.NORMAL)
                }
                Log.d(TAG, "Mode $mode handled $pkg: $reason")
                handled++
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Enforce mode $mode failed", e)
        }
        return handled
    }

    /**
     * 智能拦截专用判定：只有「强证据 + 侧载安装 + 非系统/非白名单」才允许自动卸载。
     * 用于安装、前台启动时的自动处置，任何一项不满足都只提示不卸载，避免误伤。
     */
    fun analyzeForAutoRemove(context: Context, packageName: String): String? {
        if (packageName == context.packageName) return null
        if (isWhitelisted(packageName) || isSystemApp(context, packageName)) return null
        // 应用商店安装的应用一律不自动卸载，防止误删正常软件
        if (!isSideLoaded(context, packageName)) return null

        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS
            )
            val perms = packageInfo.requestedPermissions?.toList() ?: emptyList()
            val hasOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms
            val hasKeyguard = "android.permission.DISABLE_KEYGUARD" in perms
            val hasAccessibility = "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms
            val hasInstall = "android.permission.REQUEST_INSTALL_PACKAGES" in perms
            val hasBoot = "android.permission.RECEIVE_BOOT_COMPLETED" in perms
            val hasCamera = "android.permission.CAMERA" in perms
            val declaresAdmin = try {
                packageInfo.receivers?.any {
                    it.permission == "android.permission.BIND_DEVICE_ADMIN"
                } == true
            } catch (e: Throwable) {
                false
            }
            val hasAdmin = declaresAdmin || isActiveDeviceAdmin(context, packageName)
            val hidden = !hasLauncherEntry(context, packageName)

            val lockReason = analyzeApp(context, packageName, 3, strictOnly = true)
            if (lockReason != null &&
                (hidden || hasAdmin || hasKeyguard ||
                        (hasBoot && (hasInstall || hasCamera)) ||
                        (hasOverlay && hasAccessibility && hasCamera))
            ) {
                return "$lockReason（侧载安装，二次校验通过）"
            }

            val label = try {
                pm.getApplicationLabel(packageInfo.applicationInfo).toString()
            } catch (e: Throwable) {
                ""
            }
            val nameKey = (label + " " + packageName).lowercase()

            val rootToken = ROOT_TOOL_TOKENS.firstOrNull { nameKey.contains(it) }
            if (rootToken != null) {
                return "一键 Root 工具名特征：$rootToken（侧载安装）"
            }

            val lockToken = LOCK_TOOL_TOKENS.firstOrNull { nameKey.contains(it) }
            if (lockToken != null && (hasOverlay || hasAdmin || hasKeyguard || hasAccessibility)) {
                return "应用名含锁机特征「$lockToken」且具备高风险权限（侧载安装）"
            }

            null
        } catch (e: Throwable) {
            Log.e(TAG, "Auto remove analyze failed: $packageName", e)
            null
        }
    }

    // 只认明确的「一键 Root」类工具名，避免把 Magisk、Root 检测器等正常工具误删
    private val ROOT_TOOL_TOKENS = listOf(
        "一键root", "kingroot", "root大师", "root精灵", "root助手",
        "root权限", "root工具", "root神器", "root获取", "root教程"
    )

    // 锁机类应用名特征（必须同时具备高风险权限才成立）
    private val LOCK_TOOL_TOKENS = listOf(
        "锁机", "锁屏病毒", "lockvirus", "lockscreenvirus"
    )

    /** 实时守护（护盾前台服务）是否在运行 */
    private fun isGuardRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == "com.tianmeng.security.service.SecurityShieldService"
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 是否已激活设备管理器（锁机病毒常用手段，无需权限即可查询）
     */
    fun isActiveDeviceAdmin(context: Context, packageName: String): Boolean {
        return try {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                ?: return false
            dpm.activeAdmins?.any { it.packageName == packageName } == true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 检查是否在白名单中
     */
    fun isWhitelisted(packageName: String): Boolean {
        if (SAFE_PACKAGES.contains(packageName)) return true
        if (Prefs.getWhitelist().contains(packageName)) return true
        return SYSTEM_WHITELIST_PREFIXES.any { packageName.startsWith(it) }
    }

    /**
     * 清除锁机病毒：强制停止 + 清除数据 + 卸载
     * 系统应用不会被卸载，仅强制停止
     */
    fun removeVirus(context: Context, packageName: String): Boolean {
        // 再次确认不是白名单
        if (isWhitelisted(packageName)) {
            Log.w(TAG, "Refuse to remove whitelisted app: $packageName")
            return false
        }

        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            return false
        }
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

        // 1. 强制停止
        ShellManager.forceStop(packageName)

        // 2. 锁机病毒常激活设备管理器来阻止卸载，先通过 shell 移除激活状态
        removeActiveAdmin(context, packageName)

        // 3. 清除数据
        ShellManager.clearAppData(packageName)

        // 4. 如果是用户应用，卸载；系统应用仅停用
        if (!isSystemApp) {
            var result = ShellManager.uninstallApp(packageName)
            if (!isInstalled(context, packageName)) return true

            // 设备管理器可能在卸载过程中被病毒重新激活，再移除一次
            removeActiveAdmin(context, packageName)
            result = ShellManager.uninstallPlain(packageName)
            if (!isInstalled(context, packageName)) return true

            // 多用户 / 工作资料场景兜底
            ShellManager.exec("pm uninstall --user 0 $packageName")
            if (!isInstalled(context, packageName)) return true
            ShellManager.exec("pm uninstall -k --user 0 $packageName")
            if (!isInstalled(context, packageName)) return true
            ShellManager.exec("cmd package uninstall --user 0 $packageName")

            val removed = !isInstalled(context, packageName)
            if (!removed) {
                // 系统仍拒绝卸载：强制停用 + 冻结，保证病毒无法再运行/弹悬浮窗
                Log.e(TAG, "Uninstall still failed: $packageName, ${result.stderr}")
                removeActiveAdmin(context, packageName)
                ShellManager.disableApp(packageName)
                FreezeManager.freezeApp(context, packageName, FreezeMode.NORMAL)
            }
            return removed
        } else {
            // 系统应用：尝试停用
            ShellManager.disableApp(packageName)
            return true
        }
    }

    /** 移除第三方锁机软件已激活的设备管理器，否则系统会拒绝卸载 */
    private fun removeActiveAdmin(context: Context, packageName: String) {
        try {
            if (!isActiveDeviceAdmin(context, packageName)) return
            val receivers = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_RECEIVERS
            ).receivers ?: return
            val admin = receivers.firstOrNull {
                it.permission == "android.permission.BIND_DEVICE_ADMIN"
            } ?: return
            val component = "${packageName}/${admin.name}"
            ShellManager.exec("dpm remove-active-admin $component")
            ShellManager.exec("dpm remove-active-admin --user 0 $component")
            if (isActiveDeviceAdmin(context, packageName)) {
                ShellManager.exec("pm disable-user --user 0 $packageName")
                ShellManager.exec("dpm remove-active-admin $component")
            }
            Log.d(TAG, "Removed active admin: $component")
        } catch (e: Throwable) {
            Log.w(TAG, "Remove active admin failed: $packageName", e)
        }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 实时拦截：当检测到锁机病毒在前台运行时，强制退出
     */
    fun interceptVirus(context: Context, packageName: String): Boolean {
        Log.d(TAG, "Intercepting virus: $packageName")
        if (isWhitelisted(packageName)) return false

        // 立即强制停止
        ShellManager.forceStop(packageName)

        // 返回 home
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Return home failed", e)
        }

        return true
    }
}
