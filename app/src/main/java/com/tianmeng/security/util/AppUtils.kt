package com.tianmeng.security.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.tianmeng.security.data.AppInfo
import com.tianmeng.security.data.FreezeMode
import com.tianmeng.security.data.Prefs

object AppUtils {

    /**
     * 获取所有带桌面图标的第三方应用（包含已冻结 / 已停用的应用）
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
        val resolveInfos = pm.queryIntentActivities(intent, flags)

        val frozenApps = Prefs.getFrozenApps()

        return resolveInfos.mapNotNull { info ->
            try {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                buildAppInfo(context, pkg, frozenApps)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.packageName }.sortedBy { it.appName }
    }

    /**
     * 获取已冻结的应用列表。
     *
     * 注意：不能用「桌面图标列表」推导冻结应用 —— 被 disable 的应用不会出现在
     * queryIntentActivities 结果里。这里直接按本地记录的包名逐个解析，保证冻结列表不丢数据。
     */
    fun getFrozenApps(context: Context): List<AppInfo> {
        val frozenApps = Prefs.getFrozenApps()
        if (frozenApps.isEmpty()) return emptyList()

        return frozenApps.mapNotNull { pkg ->
            try {
                buildAppInfo(context, pkg, frozenApps)
            } catch (e: Exception) {
                // 应用可能已被卸载，仍返回一个占位条目，避免列表看起来是空的
                AppInfo(
                    packageName = pkg,
                    appName = pkg,
                    icon = null,
                    isSystemApp = false,
                    isFrozen = true,
                    freezeMode = Prefs.getFreezeMode(pkg)
                )
            }
        }.sortedBy { it.appName }
    }

    private fun buildAppInfo(
        context: Context,
        pkg: String,
        frozenApps: Set<String>
    ): AppInfo? {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
        val appInfo = pm.getApplicationInfo(pkg, flags)
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isFrozen = frozenApps.contains(pkg)
        val freezeMode = if (isFrozen) Prefs.getFreezeMode(pkg) else FreezeMode.NONE

        return AppInfo(
            packageName = pkg,
            appName = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrElse { pkg },
            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
            isSystemApp = isSystem,
            isFrozen = isFrozen,
            freezeMode = freezeMode
        )
    }

    /**
     * 解析单个应用的展示信息（不存在时返回 null）
     */
    fun getAppInfo(context: Context, packageName: String): AppInfo? {
        return try {
            buildAppInfo(context, packageName, Prefs.getFrozenApps())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取全部第三方应用包名（含无桌面图标的应用，用于紧急终止）
     */
    fun getThirdPartyPackages(context: Context): List<String> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        return apps.asSequence()
            .filter { info ->
                val flags = info.flags
                (flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            }
            .map { it.packageName }
            .distinct()
            .toList()
    }

    /**
     * 获取应用图标
     */
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取应用名称
     */
    fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
            val appInfo = pm.getApplicationInfo(packageName, flags)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
