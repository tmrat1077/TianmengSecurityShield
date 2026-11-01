package com.tianmeng.security.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tianmeng.security.App
import com.tianmeng.security.MainActivity
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.ForegroundAppMonitor
import com.tianmeng.security.manager.PermissionManager
import com.tianmeng.security.manager.ShieldWatchdog
import com.tianmeng.security.manager.SmartBlockManager
import com.tianmeng.security.manager.TriggerHandler

/**
 * 安全护盾前台服务：
 * - 摇一摇检测
 * - 音量键触发（由无障碍服务转发）
 * - 锁机病毒实时拦截
 */
class SecurityShieldService : Service(), SensorEventListener {

    private val TAG = "ShieldService"

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLastValues = false

    /** 使用情况访问兜底监测前台应用（无障碍未开启时也能识别锁机病毒） */
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val foregroundMonitor = object : Runnable {
        override fun run() {
            try {
                // 定期全量巡检：兜住从不打开、也不会触发前台事件的隐藏锁机病毒
                SmartBlockManager.periodicScan(this@SecurityShieldService.applicationContext)
                if (Prefs.smartBlock && PermissionManager.hasUsageAccess(this@SecurityShieldService)) {
                    val pkg = ForegroundAppMonitor.currentForeground(this@SecurityShieldService)
                    if (pkg != null) {
                        SmartBlockManager.onForegroundPackage(
                            this@SecurityShieldService.applicationContext, pkg
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Foreground monitor failed", e)
            }
            monitorHandler.postDelayed(this, 1500L)
        }
    }

    companion object {
        const val ACTION_VOLUME_TRIGGER = "com.tianmeng.security.action.VOLUME_TRIGGER"
        private const val SHAKE_COOLDOWN = 1500L

        fun start(context: Context) {
            val intent = Intent(context, SecurityShieldService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SecurityShieldService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        startAsForeground()
        monitorHandler.postDelayed(foregroundMonitor, 1500L)
        Log.d(TAG, "Shield service created")
    }

    /**
     * Android 14 起前台服务需显式声明类型；使用 ServiceCompat 保持兼容。
     */
    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                App.NOTIFICATION_ID_SHIELD,
                buildNotification(),
                type
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_VOLUME_TRIGGER -> TriggerHandler.trigger("volume")
            ACTION_SHAKE_TRIGGER -> TriggerHandler.trigger("shake")
        }

        try {
            if (Prefs.shakeTrigger) {
                accelerometer?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                sensorManager?.unregisterListener(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register sensor failed", e)
        }

        try {
            ShieldWatchdog.arm(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Arm watchdog failed", e)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉应用 / 清后台：服务被保活，同时安排看门狗与立即重启，
        // 防止部分 ROM 把进程一起杀掉后护盾失效。
        try {
            ShieldWatchdog.arm(this)
            if (Prefs.shieldEnabled) {
                ContextCompat.startForegroundService(
                    this, Intent(this, SecurityShieldService::class.java)
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Restart after task removed failed", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!Prefs.shakeTrigger) return
        event ?: return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (hasLastValues) {
            val deltaX = Math.abs(x - lastX)
            val deltaY = Math.abs(y - lastY)
            val deltaZ = Math.abs(z - lastZ)

            val totalDelta = deltaX + deltaY + deltaZ
            if (totalDelta > TriggerHandler.shakeThreshold()) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_COOLDOWN) {
                    lastShakeTime = now
                    TriggerHandler.trigger("shake")
                }
            }
        }

        lastX = x
        lastY = y
        lastZ = z
        hasLastValues = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID_SHIELD)
            .setContentTitle("天梦安全护盾")
            .setContentText("护盾运行中，摇一摇或按音量键可拦截锁机病毒")
            .setSmallIcon(R.drawable.ic_shield_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        monitorHandler.removeCallbacks(foregroundMonitor)
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            // ignore
        }
        Log.d(TAG, "Shield service destroyed")
    }
}

private const val ACTION_SHAKE_TRIGGER = "com.tianmeng.security.action.SHAKE_TRIGGER"
