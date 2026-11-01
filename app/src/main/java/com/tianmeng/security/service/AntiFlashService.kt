package com.tianmeng.security.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tianmeng.security.App
import com.tianmeng.security.MainActivity
import com.tianmeng.security.R
import com.tianmeng.security.manager.AntiFlashManager

/**
 * 防闪保护前台服务：持续监听手电筒，识别恶意闪烁并强制关闭。
 */
class AntiFlashService : Service() {

    companion object {
        private const val TAG = "AntiFlashService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AntiFlashService::class.java)
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Start anti-flash service failed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AntiFlashService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return false
                @Suppress("DEPRECATION")
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == AntiFlashService::class.java.name
                }
            } catch (e: Throwable) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        AntiFlashManager.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AntiFlashManager.start(this)
        return START_STICKY
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                App.NOTIFICATION_ID_FLASH,
                buildNotification(),
                type
            )
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID_SHIELD)
            .setContentTitle("防闪保护运行中")
            .setContentText("手电筒被恶意打开或闪烁时会立即强制关闭")
            .setSmallIcon(R.drawable.ic_shield_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        AntiFlashManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
