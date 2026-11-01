package com.tianmeng.security.manager

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tianmeng.security.App
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.util.AppUtils

/**
 * 防闪保护：
 * 锁机病毒常通过手电筒不停开关制造闪光骚扰。
 * 这里监听全局手电筒状态，识别到「快速连续切换 / 锁屏下开启 / 可疑应用开启」时立即强制关闭。
 * 正常用户手动打开手电筒（单次开启、前台为桌面/相机）不会被干预。
 */
object AntiFlashManager {

    private const val TAG = "AntiFlash"
    private const val FLASH_WINDOW_MS = 10_000L
    private const val RAPID_THRESHOLD = 2
    private const val NOTIFY_INTERVAL_MS = 15_000L

    private var cameraManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var handler: Handler? = null
    private var running = false
    private var appContext: Context? = null

    private val torchStates = mutableMapOf<String, Boolean>()
    private val flashTimes = ArrayDeque<Long>()
    private var lastNotifyAt = 0L

    private var cachedForeground: String? = null
    private var cachedForegroundAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                torchStates.forEach { (id, on) ->
                    if (on) handleFlash(id)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Poll torch state failed", e)
            }
            handler?.postDelayed(this, 400L)
        }
    }

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        cameraManager = cm
        handler = Handler(Looper.getMainLooper())
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torchStates[cameraId] = enabled
                if (enabled) handleFlash(cameraId)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                torchStates[cameraId] = false
            }
        }
        torchCallback = callback
        try {
            cm.registerTorchCallback(callback, handler)
            running = true
            handler?.postDelayed(pollRunnable, 400L)
            Log.d(TAG, "Anti-flash started")
        } catch (e: Throwable) {
            Log.e(TAG, "Register torch callback failed", e)
            running = false
        }
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        try {
            val cm = cameraManager
            val cb = torchCallback
            if (cm != null && cb != null) cm.unregisterTorchCallback(cb)
        } catch (e: Throwable) {
            Log.w(TAG, "Unregister torch callback failed", e)
        }
        torchCallback = null
        handler = null
        torchStates.clear()
        flashTimes.clear()
        Log.d(TAG, "Anti-flash stopped")
    }

    private fun handleFlash(cameraId: String) {
        val ctx = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        while (flashTimes.isNotEmpty() && now - flashTimes.first() > FLASH_WINDOW_MS) {
            flashTimes.removeFirst()
        }
        flashTimes.addLast(now)

        val screenOff = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive.not()
        } catch (e: Throwable) {
            false
        }
        val foreground = recentForeground(ctx)
        val suspicious = foreground != null &&
                foreground != ctx.packageName &&
                !VirusManager.isWhitelisted(foreground) &&
                VirusManager.analyzeForAutoRemove(ctx, foreground) != null
        val rapid = flashTimes.size >= RAPID_THRESHOLD

        if (screenOff.not() && suspicious.not() && rapid.not()) {
            // 正常手动开启手电筒，不干预
            return
        }

        forceOff(cameraId)
        notifyBlocked(ctx, foreground)
        if (suspicious && Prefs.smartBlock) {
            try {
                SmartBlockManager.onForegroundPackage(ctx, foreground!!)
            } catch (e: Throwable) {
                Log.w(TAG, "Smart block flash source failed", e)
            }
        }
    }

    private fun recentForeground(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedForegroundAt < 5_000L) return cachedForeground
        cachedForegroundAt = now
        cachedForeground = try {
            ForegroundAppMonitor.currentForeground(context)
        } catch (e: Throwable) {
            null
        }
        return cachedForeground
    }

    private fun forceOff(cameraId: String) {
        val cm = cameraManager ?: return
        try {
            cm.setTorchMode(cameraId, false)
            torchStates[cameraId] = false
            Log.d(TAG, "Torch forced off: $cameraId")
        } catch (e: Throwable) {
            Log.w(TAG, "Force off torch failed: $cameraId", e)
            try {
                val ids = cm.cameraIdList.filter { id ->
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                ids.forEach { id ->
                    runCatching { cm.setTorchMode(id, false) }
                }
            } catch (e2: Throwable) {
                Log.w(TAG, "Force off all flash cameras failed", e2)
            }
        }
    }

    private fun notifyBlocked(context: Context, foreground: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        try {
            val name = foreground?.let { pkg ->
                runCatching { AppUtils.getAppName(context, pkg) }.getOrDefault(pkg)
            }
            val text = if (name != null) {
                "检测到「$name」恶意打开手电筒，已强制关闭"
            } else {
                "检测到恶意手电筒闪烁，已强制关闭"
            }
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = NotificationCompat.Builder(context, App.CHANNEL_ID_ALERT)
                .setSmallIcon(R.drawable.ic_shield_warning)
                .setContentTitle("已拦截闪光骚扰")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(App.NOTIFICATION_ID_FLASH, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "Notify flash block failed", e)
        }
    }
}
