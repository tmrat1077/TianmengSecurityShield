package com.tianmeng.security.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tianmeng.security.MainActivity
import com.tianmeng.security.R
import com.tianmeng.security.manager.MuteManager

/**
 * 一键静音前台服务：持续压制所有音频流，避免被锁机病毒反复调高音量。
 */
class MuteGuardService : Service() {

    companion object {
        private const val CHANNEL_ID = "mute_guard"
        const val NOTIFICATION_ID = 1004

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MuteGuardService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MuteGuardService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        MuteManager.enable(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MuteManager.isEnabled()) MuteManager.enable(this)
        return START_STICKY
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Throwable) {
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mute_notification_title))
            .setContentText(getString(R.string.mute_notification_text))
            .setSmallIcon(R.drawable.ic_volume_off)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "一键静音", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "一键静音守卫运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        MuteManager.disable(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
