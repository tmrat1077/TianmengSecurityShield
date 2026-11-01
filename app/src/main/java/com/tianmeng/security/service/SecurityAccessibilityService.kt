package com.tianmeng.security.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.tianmeng.security.data.FreezeMode
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.FreezeManager
import com.tianmeng.security.manager.SmartBlockManager
import com.tianmeng.security.manager.TriggerHandler
import com.tianmeng.security.ui.FrozenAppActivity
import com.tianmeng.security.util.AppUtils

/**
 * 无障碍服务：
 * - 音量键触发拦截（直接调用 TriggerHandler，不依赖后台启动前台服务）
 * - 摇一摇触发拦截（服务自身注册加速度传感器，双保险）
 * - 跟踪前台应用与可见窗口（供锁机病毒判定）
 * - 监控停用冻结应用的启动，显示接管提示
 */
class SecurityAccessibilityService : AccessibilityService(), SensorEventListener {

    private val TAG = "AccessibilitySvc"

    private var lastVolumePressTime = 0L
    private var volumePressCount = 0
    private val VOLUME_PRESS_WINDOW = 1500L

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLastValues = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer != null) {
                sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Init sensor failed", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        // 连接后重新注册，防止系统重连后监听失效
        try {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Register sensor failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == "com.android.systemui") return

        if (eventPackage != packageName) {
            foregroundPackage = eventPackage
        }

        // 在无障碍事件线程刷新可见窗口包名缓存，供触发时快速读取
        refreshWindowPackages()

        handleWindowChange(eventPackage)

        // 智能拦截：应用进入前台时异步判定，真实病毒直接自动卸载
        if (Prefs.smartBlock) {
            SmartBlockManager.onForegroundPackage(applicationContext, eventPackage)
        }
    }

    private fun refreshWindowPackages() {
        try {
            val packages = mutableListOf<String>()
            for (window in windows) {
                val pkg = window?.root?.packageName?.toString() ?: continue
                if (pkg.isNotBlank() && pkg !in packages) packages.add(pkg)
            }
            if (packages.isNotEmpty()) {
                windowPackages = packages
                if (foregroundPackage == null) foregroundPackage = packages.first()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Refresh windows failed", e)
        }
    }

    /**
     * 处理窗口变化：检测停用冻结的应用是否被尝试启动
     */
    private fun handleWindowChange(eventPackage: String) {
        if (eventPackage == packageName) return
        if (!FreezeManager.isFrozen(eventPackage)) return

        if (FreezeManager.getFreezeMode(eventPackage) == FreezeMode.DISABLED) {
            showFrozenAppMessage(eventPackage)
        }
    }

    private fun showFrozenAppMessage(pkg: String) {
        val appName = AppUtils.getAppName(this, pkg)
        val intent = Intent(this, FrozenAppActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(FrozenAppActivity.EXTRA_PACKAGE, pkg)
            putExtra(FrozenAppActivity.EXTRA_APP_NAME, appName)
        }
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Show frozen message failed", e)
        }
    }

    /**
     * 音量键：按用户设置的按键与连按次数触发（不消费事件，系统音量照常调节）
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        if (!Prefs.volumeTrigger) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val targetKey = if (Prefs.volumeKey == 1) {
            KeyEvent.KEYCODE_VOLUME_UP
        } else {
            KeyEvent.KEYCODE_VOLUME_DOWN
        }
        if (event.keyCode != targetKey) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastVolumePressTime > VOLUME_PRESS_WINDOW) {
            volumePressCount = 1
        } else {
            volumePressCount++
        }
        lastVolumePressTime = now

        if (volumePressCount >= Prefs.volumePressCount) {
            volumePressCount = 0
            Log.d(TAG, "Volume key trigger")
            TriggerHandler.trigger("volume")
        }
        return false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (!Prefs.shakeTrigger) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (hasLastValues) {
            val delta = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ)
            if (delta > TriggerHandler.shakeThreshold()) {
                TriggerHandler.trigger("shake")
            }
        }

        lastX = x
        lastY = y
        lastZ = z
        hasLastValues = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Throwable) {
            // ignore
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** 当前前台应用包名，由无障碍事件持续更新 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        @Volatile
        private var instance: SecurityAccessibilityService? = null

        /** 无障碍手势注入是否可用（自动点击器优先走该通道） */
        fun isGestureReady(): Boolean = instance != null

        /** 在屏幕坐标 (x, y) 注入一次点击；不可用时返回 false，由调用方回退 Shell */
        fun tapAt(x: Int, y: Int, pressMs: Long = 60L): Boolean {
            val service = instance ?: return false
            return try {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(
                    path, 0, pressMs.coerceIn(20L, 1000L)
                )
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                service.dispatchGesture(gesture, null, null)
            } catch (e: Throwable) {
                false
            }
        }

        @Volatile
        private var windowPackages: List<String> = emptyList()

        /**
         * 读取当前所有可见窗口的包名（锁机病毒的覆盖窗口也能识别）
         */
        fun currentWindowPackages(): List<String> = windowPackages

        /**
         * 通过无障碍服务启动 Activity：拥有后台启动豁免，
         * 保证从悬浮窗点「是」后一定能打开卸载页。
         */
        fun launchActivity(context: Context, intent: Intent): Boolean {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val service = instance
            if (service != null) {
                try {
                    service.startActivity(intent)
                    return true
                } catch (e: Throwable) {
                    Log.e("AccessibilitySvc", "Launch from service failed", e)
                }
            }
            return try {
                context.startActivity(intent)
                true
            } catch (e: Throwable) {
                Log.e("AccessibilitySvc", "Launch from context failed", e)
                false
            }
        }
    }
}
