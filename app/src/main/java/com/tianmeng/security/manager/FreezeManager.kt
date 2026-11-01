package com.tianmeng.security.manager

import android.content.Context
import android.util.Log
import com.tianmeng.security.data.FreezeMode
import com.tianmeng.security.data.Prefs

/**
 * 应用冻结管理器
 *
 * 常规冻结 (NORMAL): pm disable-user --user 0
 *   - 应用从桌面消失，无法启动
 *   - 数据保留，可随时解冻
 *
 * 停用冻结 (DISABLED): pm disable
 *   - 应用图标保留（可能变灰），点击后由无障碍服务拦截并显示接管提示
 *   - 数据保留，可随时解冻
 */
object FreezeManager {

    private const val TAG = "FreezeManager"

    /**
     * 冻结应用
     */
    fun freezeApp(context: Context, packageName: String, mode: FreezeMode): Boolean {
        if (packageName == context.packageName) {
            Log.w(TAG, "Cannot freeze self")
            return false
        }

        val result = when (mode) {
            FreezeMode.NORMAL -> ShellManager.disableAppAndHide(packageName)
            FreezeMode.DISABLED -> ShellManager.disableApp(packageName)
            FreezeMode.NONE -> return false
        }

        if (result.isSuccess) {
            Prefs.addFrozenApp(packageName)
            Prefs.setFreezeMode(packageName, mode)
            Log.d(TAG, "Frozen $packageName with mode $mode")
            return true
        } else {
            Log.e(TAG, "Freeze failed: ${result.stderr}")
            return false
        }
    }

    /**
     * 解冻应用
     */
    fun unfreezeApp(context: Context, packageName: String): Boolean {
        val result = ShellManager.enableApp(packageName)
        if (result.isSuccess) {
            Prefs.removeFrozenApp(packageName)
            Log.d(TAG, "Unfrozen $packageName")
            return true
        } else {
            // 即使命令失败，也从记录中移除
            Prefs.removeFrozenApp(packageName)
            Log.e(TAG, "Unfreeze failed: ${result.stderr}")
            return false
        }
    }

    /**
     * 检查应用是否被冻结
     */
    fun isFrozen(packageName: String): Boolean {
        return Prefs.getFrozenApps().contains(packageName)
    }

    /**
     * 获取冻结模式
     */
    fun getFreezeMode(packageName: String): FreezeMode {
        return Prefs.getFreezeMode(packageName)
    }

    /**
     * 获取所有冻结的应用包名
     */
    fun getFrozenPackages(): Set<String> {
        return Prefs.getFrozenApps()
    }

    /**
     * 切换冻结状态
     */
    fun toggleFreeze(context: Context, packageName: String, mode: FreezeMode): Boolean {
        return if (isFrozen(packageName)) {
            unfreezeApp(context, packageName)
        } else {
            freezeApp(context, packageName, mode)
        }
    }
}
