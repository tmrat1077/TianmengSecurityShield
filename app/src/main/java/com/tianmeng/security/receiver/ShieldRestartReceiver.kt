package com.tianmeng.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.service.SecurityShieldService

/**
 * 看门狗定时广播：进程/服务被系统或 ROM 杀掉后自动拉起，
 * 保证用户从最近任务划掉应用、清后台后护盾仍在运行。
 */
class ShieldRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (Prefs.shieldEnabled) {
                ContextCompat.startForegroundService(
                    context, Intent(context, SecurityShieldService::class.java)
                )
            }
        } catch (e: Throwable) {
            Log.e("ShieldRestart", "Restart shield failed", e)
        }
    }
}
