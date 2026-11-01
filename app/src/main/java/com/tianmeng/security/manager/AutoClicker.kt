package com.tianmeng.security.manager

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tianmeng.security.R
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.service.AutoClickerService
import com.tianmeng.security.service.SecurityAccessibilityService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 灵动岛自动点击器（多点上点版）：
 * - 顶部居中胶囊悬浮窗，展开为控制面板
 * - 多点位顺序循环点击，可设置间隔、按下时长、随机偏移、点击上限
 * - 防护：启动 3 秒倒计时防误触、息屏 / 锁屏自动停止、点击上限自动停止
 */
object AutoClicker {

    private const val TAG = "AutoClicker"
    private const val MIN_INTERVAL = 50L
    private const val MIN_PRESS = 60L
    private const val MAX_POINTS = 10
    private const val COUNTDOWN_SECONDS = 3
    private const val RANDOM_OFFSET_DP = 8

    private val main = Handler(Looper.getMainLooper())
    private val shellExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "auto-clicker-shell").apply { isDaemon = true }
    }
    private val shellPending = AtomicBoolean(false)

    private var context: Context? = null
    private var wm: WindowManager? = null
    private var islandView: View? = null
    private var panelView: View? = null
    private var pickerView: View? = null
    private val markerViews = mutableListOf<View>()

    private var tvStatus: TextView? = null
    private var tvCount: TextView? = null
    private var tvInterval: TextView? = null
    private var tvPress: TextView? = null
    private var tvPoints: TextView? = null
    private var btnToggle: TextView? = null
    private var btnLimit: TextView? = null
    private var btnClear: TextView? = null
    private var swRandom: Switch? = null

    private data class Point(val x: Int, val y: Int)

    private val points = mutableListOf<Point>()

    private var running = false
    private var pendingStart = false
    private var countdownRemaining = 0
    private var expanded = false
    private var intervalMs = 200L
    private var pressMs = MIN_PRESS
    private var limit = 0
    private var randomOn = true
    private var currentIndex = 0
    private var clicks = 0

    fun isShown(): Boolean = islandView != null

    fun show(ctx: Context) {
        if (islandView != null) return
        context = ctx.applicationContext
        if (!EmergencyOverlay.canDraw(ctx)) {
            toast(R.string.island_need_overlay)
            return
        }
        try {
            loadPrefs()
            val wmLocal = ctx.getSystemService(WindowManager::class.java)
            val view = LayoutInflater.from(ctx).inflate(R.layout.layout_overlay_auto_clicker, null)
            islandView = view
            wm = wmLocal
            panelView = view.findViewById(R.id.islandPanel)
            tvStatus = view.findViewById(R.id.tvIslandStatus)
            tvCount = view.findViewById(R.id.tvIslandCount)
            tvInterval = view.findViewById(R.id.tvIslandInterval)
            tvPress = view.findViewById(R.id.tvIslandPress)
            tvPoints = view.findViewById(R.id.tvIslandPoints)
            btnToggle = view.findViewById(R.id.btnIslandToggle)
            btnLimit = view.findViewById(R.id.btnIslandLimit)
            btnClear = view.findViewById(R.id.btnIslandClear)
            swRandom = view.findViewById(R.id.swIslandRandom)

            view.findViewById<View>(R.id.islandPill).setOnClickListener { toggleExpanded() }
            view.findViewById<View>(R.id.btnIslandClose).setOnClickListener {
                AutoClickerService.stop(ctx)
            }
            view.findViewById<View>(R.id.btnIslandPick).setOnClickListener { openPicker() }
            btnToggle?.setOnClickListener { toggleRunning() }
            btnClear?.setOnClickListener { clearPoints() }
            btnLimit?.setOnClickListener { cycleLimit() }
            swRandom?.isChecked = randomOn
            swRandom?.setOnCheckedChangeListener { _, checked ->
                randomOn = checked
                Prefs.clickerRandom = checked
            }

            val intervalBar = view.findViewById<SeekBar>(R.id.sbIslandInterval)
            intervalBar.progress = (intervalMs - MIN_INTERVAL).toInt()
            intervalBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    intervalMs = MIN_INTERVAL + progress
                    Prefs.clickerInterval = intervalMs.toInt()
                    updateIntervalText()
                    if (!running && !pendingStart) refreshStatus()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })

            val pressBar = view.findViewById<SeekBar>(R.id.sbIslandPress)
            pressBar.progress = (pressMs - MIN_PRESS).toInt()
            pressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pressMs = MIN_PRESS + progress
                    Prefs.clickerPress = pressMs.toInt()
                    updatePressText()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })

            val params = overlayParams(
                width = WindowManager.LayoutParams.WRAP_CONTENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(8)
            }
            wmLocal.addView(view, params)
            updateIntervalText()
            updatePressText()
            updateLimitText()
            updatePointsText()
            rebuildMarkers()
            refreshStatus()
        } catch (e: Throwable) {
            Log.e(TAG, "show island failed", e)
            islandView = null
            wm = null
            toast(R.string.island_need_overlay)
        }
    }

    fun hide() {
        stopClicking(null)
        removePicker()
        rebuildMarkers(clear = true)
        val wmLocal = wm
        islandView?.let { view ->
            try {
                wmLocal?.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "remove island failed", e)
            }
        }
        islandView = null
        panelView = null
        pickerView = null
        wm = null
        context = null
        points.clear()
        expanded = false
        clicks = 0
        currentIndex = 0
        tvStatus = null
        tvCount = null
        tvInterval = null
        tvPress = null
        tvPoints = null
        btnToggle = null
        btnLimit = null
        btnClear = null
        swRandom = null
    }

    private fun loadPrefs() {
        points.clear()
        Prefs.clickerPoints
            ?.split("|")
            ?.forEach { entry ->
                val parts = entry.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toIntOrNull()
                    val y = parts[1].toIntOrNull()
                    if (x != null && y != null && points.size < MAX_POINTS) {
                        points.add(Point(x, y))
                    }
                }
            }
        intervalMs = Prefs.clickerInterval.toLong().coerceIn(MIN_INTERVAL, 2000L)
        pressMs = Prefs.clickerPress.toLong().coerceIn(MIN_PRESS, 500L)
        limit = Prefs.clickerLimit
        randomOn = Prefs.clickerRandom
    }

    private fun savePoints() {
        Prefs.clickerPoints = if (points.isEmpty()) {
            null
        } else {
            points.joinToString("|") { "${it.x},${it.y}" }
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        panelView?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) refreshStatus()
    }

    private fun pokeExpanded(value: Boolean) {
        expanded = value
        panelView?.visibility = if (value) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openPicker() {
        val wmLocal = wm ?: return
        if (pickerView != null) return
        val view = LayoutInflater.from(context)
            .inflate(R.layout.layout_overlay_click_picker, null)
        pickerView = view
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                addPoint(event.rawX.toInt(), event.rawY.toInt())
                removePicker()
            }
            true
        }
        pokeExpanded(false)
        try {
            wmLocal.addView(
                view,
                overlayParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "add picker failed", e)
            removePicker()
        }
    }

    private fun removePicker() {
        val view = pickerView ?: return
        try {
            wm?.removeView(view)
        } catch (e: Throwable) {
            Log.w(TAG, "remove picker failed", e)
        }
        pickerView = null
    }

    private fun addPoint(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        if (points.size >= MAX_POINTS) {
            toast(R.string.island_points_full)
            pokeExpanded(true)
            return
        }
        points.add(Point(x, y))
        savePoints()
        updatePointsText()
        rebuildMarkers()
        pokeExpanded(true)
        refreshStatus()
    }

    private fun clearPoints() {
        if (points.isEmpty()) return
        points.clear()
        currentIndex = 0
        savePoints()
        stopClicking(null)
        updatePointsText()
        rebuildMarkers()
        refreshStatus()
    }

    private fun rebuildMarkers(clear: Boolean = false) {
        val wmLocal = wm
        val ctx = context
        markerViews.forEach { view ->
            try {
                wmLocal?.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "remove marker failed", e)
            }
        }
        markerViews.clear()
        if (clear || wmLocal == null || ctx == null) return
        points.forEach { point ->
            try {
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.layout_overlay_click_target, null)
                val params = markerParams(point)
                wmLocal.addView(view, params)
                markerViews.add(view)
            } catch (e: Throwable) {
                Log.e(TAG, "add marker failed", e)
            }
        }
    }

    private fun markerParams(point: Point): WindowManager.LayoutParams {
        val half = dp(20)
        return overlayParams(
            dp(40), dp(40),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x - half
            y = point.y - half
        }
    }

    private fun toggleRunning() {
        if (pendingStart) {
            cancelCountdown()
            return
        }
        if (running) {
            stopClicking(null)
            return
        }
        beginStart()
    }

    private fun beginStart() {
        if (points.isEmpty()) {
            toast(R.string.island_no_points)
            pokeExpanded(true)
            refreshStatus()
            return
        }
        if (!canClick()) {
            tvStatus?.text = context?.getString(R.string.island_status_no_cap)
            toast(R.string.island_need_capability)
            pokeExpanded(true)
            return
        }
        pendingStart = true
        countdownRemaining = COUNTDOWN_SECONDS
        clicks = 0
        currentIndex = 0
        updateCount()
        btnToggle?.setText(R.string.island_cancel)
        tvStatus?.text = context?.getString(R.string.island_countdown, countdownRemaining)
        main.removeCallbacks(startTask)
        main.postDelayed(startTask, 1000)
    }

    private fun cancelCountdown() {
        pendingStart = false
        countdownRemaining = 0
        main.removeCallbacks(startTask)
        btnToggle?.setText(R.string.island_start)
        refreshStatus()
    }

    private val startTask = object : Runnable {
        override fun run() {
            if (!pendingStart) return
            countdownRemaining--
            if (countdownRemaining > 0) {
                tvStatus?.text = context?.getString(R.string.island_countdown, countdownRemaining)
                main.postDelayed(this, 1000)
            } else {
                startRunning()
            }
        }
    }

    private fun startRunning() {
        pendingStart = false
        countdownRemaining = 0
        running = true
        btnToggle?.setText(R.string.island_stop)
        refreshStatus()
        main.removeCallbacks(clickTask)
        main.post(clickTask)
    }

    private fun stopClicking(reasonRes: Int?) {
        val wasActive = running || pendingStart
        running = false
        pendingStart = false
        countdownRemaining = 0
        main.removeCallbacks(clickTask)
        main.removeCallbacks(startTask)
        btnToggle?.setText(R.string.island_start)
        if (reasonRes != null && wasActive) {
            tvStatus?.text = context?.getString(reasonRes)
        } else {
            refreshStatus()
        }
    }

    private val clickTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (screenUnavailable()) {
                stopClicking(R.string.island_screen_off_stop)
                return
            }
            if (points.isEmpty()) {
                stopClicking(R.string.island_no_points)
                return
            }
            performClick()
            if (running) {
                main.postDelayed(this, nextInterval())
            }
        }
    }

    private fun nextInterval(): Long {
        if (!randomOn) return intervalMs
        val jitter = (intervalMs * 0.2).toLong().coerceAtLeast(1L)
        return (intervalMs + Random.nextLong(-jitter, jitter + 1)).coerceAtLeast(MIN_INTERVAL)
    }

    private fun performClick() {
        if (points.isEmpty()) return
        if (currentIndex !in points.indices) currentIndex = 0
        val point = points[currentIndex]
        val offset = if (randomOn) dp(RANDOM_OFFSET_DP) else 0
        val x = (point.x + if (randomOn) Random.nextInt(-offset, offset + 1) else 0)
            .coerceIn(0, screenWidth() - 1)
        val y = (point.y + if (randomOn) Random.nextInt(-offset, offset + 1) else 0)
            .coerceIn(0, screenHeight() - 1)
        val press = pressMs.coerceAtLeast(MIN_PRESS)
        if (SecurityAccessibilityService.tapAt(x, y, press)) {
            onTapped()
            return
        }
        if (ShellManager.currentMode == ShellManager.Mode.NONE) {
            tvStatus?.text = context?.getString(R.string.island_status_no_cap)
            return
        }
        if (!shellPending.compareAndSet(false, true)) return
        shellExecutor.execute {
            val ok = try {
                ShellManager.exec("input swipe $x $y $x $y $press").isSuccess
            } catch (e: Throwable) {
                Log.e(TAG, "shell tap failed", e)
                false
            } finally {
                shellPending.set(false)
            }
            if (ok) main.post { onTapped() }
        }
    }

    private fun onTapped() {
        val clickedIndex = currentIndex
        clicks++
        updateCount()
        if (clickedIndex in markerViews.indices) {
            markerViews[clickedIndex].animate()?.alpha(0.35f)?.setDuration(60)?.withEndAction {
                markerViews.getOrNull(clickedIndex)?.animate()?.alpha(1f)?.setDuration(60)?.start()
            }?.start()
        }
        if (limit > 0 && clicks >= limit) {
            stopClicking(R.string.island_limit_reached)
            return
        }
        currentIndex = (currentIndex + 1) % points.size
        if (running) {
            tvStatus?.text = context?.getString(
                R.string.island_status_running, points.size, currentIndex + 1, clicks
            )
        }
    }

    private fun screenUnavailable(): Boolean {
        val ctx = context ?: return false
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return true
        val km = ctx.getSystemService(KeyguardManager::class.java)
        return km != null && km.isKeyguardLocked
    }

    private fun cycleLimit() {
        val values = intArrayOf(0, 100, 500, 2000, 5000)
        val next = (values.indexOf(limit).let { if (it < 0) 0 else it } + 1) % values.size
        limit = values[next]
        Prefs.clickerLimit = limit
        updateLimitText()
    }

    private fun updateLimitText() {
        btnLimit?.text = if (limit <= 0) {
            context?.getString(R.string.island_limit_unlimited)
        } else {
            context?.getString(R.string.island_limit_value, limit)
        }
    }

    private fun updateCount() {
        tvCount?.text = clicks.toString()
    }

    private fun updateIntervalText() {
        tvInterval?.text = context?.getString(R.string.island_interval_value, intervalMs)
    }

    private fun updatePressText() {
        tvPress?.text = context?.getString(R.string.island_press_value, pressMs)
    }

    private fun updatePointsText() {
        tvPoints?.text = context?.getString(R.string.island_points_count, points.size, MAX_POINTS)
    }

    private fun refreshStatus() {
        if (!running && !pendingStart) btnToggle?.setText(R.string.island_start)
        tvStatus?.text = when {
            !canClick() -> context?.getString(R.string.island_status_no_cap)
            points.isEmpty() -> context?.getString(R.string.island_status_idle)
            running -> context?.getString(
                R.string.island_status_running, points.size, currentIndex + 1, clicks
            )
            pendingStart -> context?.getString(R.string.island_countdown, countdownRemaining)
            else -> context?.getString(
                R.string.island_status_pos, points.size, intervalMs
            )
        }
    }

    private fun canClick(): Boolean {
        return SecurityAccessibilityService.isGestureReady() ||
                ShellManager.currentMode != ShellManager.Mode.NONE
    }

    private fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun screenWidth(): Int = context?.resources?.displayMetrics?.widthPixels ?: 1080

    private fun screenHeight(): Int = context?.resources?.displayMetrics?.heightPixels ?: 2400

    private fun dp(value: Int): Int {
        val density = context?.resources?.displayMetrics?.density ?: 2f
        return (value * density).toInt()
    }

    private fun toast(resId: Int) {
        main.post {
            Toast.makeText(context ?: return@post, resId, Toast.LENGTH_SHORT).show()
        }
    }
}
