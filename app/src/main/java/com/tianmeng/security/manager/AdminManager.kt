package com.tianmeng.security.manager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tianmeng.security.R
import com.tianmeng.security.admin.ShieldDeviceAdminReceiver
import com.tianmeng.security.manager.ShellManager

/**
 * 设备管理器管理：激活后应用无法被直接卸载，且随系统保持后台守护。
 */
object AdminManager {

    private const val TAG = "AdminManager"

    private fun componentName(context: Context) =
        ComponentName(context, ShieldDeviceAdminReceiver::class.java)

    private fun policyManager(context: Context): DevicePolicyManager? =
        try {
            context.getSystemService(DevicePolicyManager::class.java)
        } catch (e: Throwable) {
            null
        }

    fun isActive(context: Context): Boolean {
        return try {
            policyManager(context)?.isAdminActive(componentName(context)) == true
        } catch (e: Throwable) {
            Log.w(TAG, "Query admin state failed", e)
            false
        }
    }

    /** 设备管理器只对已 Root 的设备开放 */
    fun hasRoot(): Boolean {
        return ShellManager.currentMode == ShellManager.Mode.ROOT
    }

    /** 打开系统「激活设备管理权限」页面 */
    fun requestIntent(context: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.admin_explain)
            )
        }
    }

    fun deactivate(context: Context): Boolean {
        return try {
            policyManager(context)?.removeActiveAdmin(componentName(context))
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Deactivate admin failed", e)
            false
        }
    }
}
