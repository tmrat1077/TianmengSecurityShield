package com.tianmeng.security.manager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tianmeng.security.App
import com.tianmeng.security.R
import com.tianmeng.security.service.SecurityAccessibilityService
import com.tianmeng.security.ui.UninstallPickerActivity

/**
 * 紧急确认悬浮窗：不需要启动 Activity，直接叠加在任意界面（包括锁机病毒的全屏覆盖层）之上。
 */
object EmergencyOverlay {

    private const val TAG = "EmergencyOverlay"
    private const val COUNTDOWN_SECONDS = 6

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var countdownTask: Runnable? = null
    @Volatile
    private var suspectedPackages: List<String> = emptyList()

    fun canDraw(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun show(source: String, suspected: List<String>) {
        suspectedPackages = suspected
        handler.post { addOverlay() }
    }

    /**
     * 悬浮窗先弹出、后分析完成时回填可疑应用，点「是」时卸载页会自动勾选。
     */
    fun updateSuspected(suspected: List<String>) {
        suspectedPackages = suspected
    }

    private fun addOverlay() {
        if (overlayView != null) return
        val context = App.instance
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.layout_overlay_emergency, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
            @Suppress("DEPRECATION")
            params.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )

            val wm = context.getSystemService(WindowManager::class.java)
            wm.addView(view, params)

            overlayView = view
            windowManager = wm
            hideSystemBars(view)

            val tvKilled = view.findViewById<TextView>(R.id.tvOverlayKilled)
            view.findViewById<View>(R.id.btnOverlayYes).setOnClickListener {
                confirmYes()
            }
            view.findViewById<View>(R.id.btnOverlayNo).setOnClickListener {
                confirmNo()
            }

            startCountdown(view, tvKilled)
        } catch (e: Throwable) {
            Log.e(TAG, "Add overlay failed", e)
            overlayView = null
            windowManager = null
            // 悬浮窗失败时退回通知 + 卸载页
            showFallbackUi(context, suspectedPackages)
        }
    }

    /** 悬浮窗也隐藏状态栏/导航栏，铺满全屏，避免下方导航栏露出 */
    @Suppress("DEPRECATION")
    private fun hideSystemBars(view: View) {
        view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.apply {
                hide(android.view.WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        view.setOnSystemUiVisibilityChangeListener { visibility ->
            val navHidden = (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
            val fullscreen = (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
            if (!navHidden || !fullscreen) {
                view.post { if (overlayView === view) hideSystemBars(view) }
            }
        }
        view.post {
            if (overlayView === view && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.hide(android.view.WindowInsets.Type.systemBars())
            }
        }
    }

    private fun startCountdown(view: View, tvKilled: TextView) {
        var remaining = COUNTDOWN_SECONDS
        val tvCountdown = view.findViewById<TextView>(R.id.tvOverlayCountdown)

        countdownTask = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvKilled.text = App.instance.getString(
                    R.string.emergency_killed_count,
                    EmergencyEngine.killedCount
                )
                tvCountdown.text = if (remaining > 0) {
                    App.instance.getString(R.string.emergency_countdown_n, remaining)
                } else {
                    ""
                }
                if (remaining <= 0) {
                    confirmYes()
                    return
                }
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(countdownTask!!)
    }

    private fun confirmYes() {
        val context = App.instance
        val target = UninstallPickerActivity.createIntent(context, suspectedPackages)
        dismiss()
        EmergencyEngine.disengage()

        val launched = SecurityAccessibilityService.launchActivity(context, target)
        if (!launched) {
            try {
                context.startActivity(target)
            } catch (e: Throwable) {
                Log.e(TAG, "Launch uninstall page failed", e)
                notifyFallback(context, target)
            }
        }
    }

    private fun confirmNo() {
        dismiss()
        EmergencyEngine.disengage()
        toast(App.instance.getString(R.string.emergency_cancelled))
    }

    /**
     * 没有悬浮窗权限时：走通知 + 卸载页兜底
     */
    fun showFallbackUi(context: Context, suspected: List<String>) {
        val target = UninstallPickerActivity.createIntent(context, suspected)
        if (!SecurityAccessibilityService.launchActivity(context, target)) {
            try {
                context.startActivity(target)
            } catch (e: Throwable) {
                Log.e(TAG, "Fallback launch failed", e)
                notifyFallback(context, target)
            }
        }
    }

    private fun notifyFallback(context: Context, target: Intent) {
        try {
            if (Build.VERSION.SDK_INT < 33 ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val pending = PendingIntent.getActivity(
                    context, 7, target,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(context, App.CHANNEL_ID_ALERT)
                    .setSmallIcon(R.drawable.ic_shield_warning)
                    .setContentTitle(context.getString(R.string.emergency_title))
                    .setContentText(context.getString(R.string.overlay_not_granted_hint))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setFullScreenIntent(pending, true)
                    .setContentIntent(pending)
                    .build()
                NotificationManagerCompat.from(context)
                    .notify(App.NOTIFICATION_ID_ALERT, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Notify fallback failed", e)
        }
    }

    private fun dismiss() {
        countdownTask?.let { handler.removeCallbacks(it) }
        countdownTask = null
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Throwable) {
            Log.e(TAG, "Remove overlay failed", e)
        }
        overlayView = null
        windowManager = null
    }

    private fun toast(message: String) {
        handler.post {
            try {
                android.widget.Toast.makeText(App.instance, message, android.widget.Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }
}
