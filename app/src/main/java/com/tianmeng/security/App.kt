package com.tianmeng.security

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.MuteManager
import com.tianmeng.security.manager.RootGuard
import com.tianmeng.security.manager.ShellManager
import com.tianmeng.security.util.CrashLogger

class App : Application() {

    companion object {
        const val CHANNEL_ID_SHIELD = "shield_service"
        const val CHANNEL_ID_ALERT = "shield_alert"
        const val NOTIFICATION_ID_SHIELD = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_FLASH = 1003

        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
        Prefs.init(this)

        // 静音服务被系统杀掉后可能残留静音状态，启动时恢复音量
        try {
            MuteManager.recoverIfStale(this)
        } catch (e: Throwable) {
            // 忽略，避免影响启动
        }

        // 关键修复：Root/Shizuku 探测放到后台线程，绝不能在主线程等 su 授权。
        ShellManager.init()

        // 格机防护（仅 Root 生效，会在后台等待 Root 探测结果）
        try {
            if (Prefs.rootGuard) RootGuard.start(this)
        } catch (e: Throwable) {
            // 忽略，避免影响启动
        }

        try {
            createNotificationChannels()
        } catch (e: Throwable) {
            // 通知渠道异常不应影响启动
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val shieldChannel = NotificationChannel(
            CHANNEL_ID_SHIELD,
            "护盾运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "天梦安全护盾后台守护通知"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            "病毒拦截告警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "检测到锁机病毒时的紧急告警"
            setShowBadge(true)
            enableVibration(true)
        }

        manager.createNotificationChannel(shieldChannel)
        manager.createNotificationChannel(alertChannel)
    }
}
