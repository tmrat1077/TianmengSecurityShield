package com.tianmeng.security.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.service.SecurityShieldService

/**
 * 设备管理器：激活后系统不允许直接卸载本应用，
 * 并在系统尝试取消激活时给出警告提示。
 */
class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {

    private val tag = "ShieldDeviceAdmin"

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(tag, "Device admin enabled")
        Prefs.shieldEnabled = true
        try {
            SecurityShieldService.start(context)
        } catch (e: Throwable) {
            Log.w(tag, "Start shield after admin enabled failed", e)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(tag, "Device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.admin_disable_warning)
    }
}
