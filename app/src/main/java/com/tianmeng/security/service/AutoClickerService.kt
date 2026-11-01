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
import com.tianmeng.security.App
import com.tianmeng.security.MainActivity
import com.tianmeng.security.R
import com.tianmeng.security.manager.AutoClicker

/**
 * 灵动岛自动点击器前台服务：保证锁屏/切到其他应用时悬浮岛与点击循环不被回收。
 */
class AutoClickerService : Service() {

    companion object {
        private const val CHANNEL_ID = "auto_clicker"
        const val NOTIFICATION_ID = 1003
        const val ACTION_STOP = "com.tianmeng.security.action.STOP_AUTO_CLICKER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, AutoClickerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoClickerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        AutoClicker.show(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AutoClicker.hide()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AutoClicker.isShown()) {
            AutoClicker.show(this)
            if (!AutoClicker.isShown()) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
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
            .setContentTitle(getString(R.string.island_notification_title))
            .setContentText(getString(R.string.island_notification_text))
            .setSmallIcon(R.drawable.ic_bolt)
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
            CHANNEL_ID, "灵动岛自动点击", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "灵动岛自动点击器运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        AutoClicker.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
