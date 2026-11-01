package com.tianmeng.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.ShieldWatchdog
import com.tianmeng.security.service.AntiFlashService
import com.tianmeng.security.service.SecurityShieldService

/**
 * 开机自启接收器
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("BootReceiver", "Boot completed, restoring services")
                try {
                    if (Prefs.shieldEnabled) {
                        SecurityShieldService.start(context)
                    }
                    if (Prefs.antiFlash && context.checkSelfPermission(
                            android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        AntiFlashService.start(context)
                    }
                    ShieldWatchdog.arm(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Start service failed", e)
                }
            }
        }
    }
}
