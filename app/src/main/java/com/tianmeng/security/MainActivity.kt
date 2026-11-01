package com.tianmeng.security

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import android.widget.RadioGroup
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.AdminManager
import com.tianmeng.security.manager.KeepAliveManager
import com.tianmeng.security.manager.MaintenanceManager
import com.tianmeng.security.manager.PermissionManager
import com.tianmeng.security.manager.RootGuard
import com.tianmeng.security.manager.ShellManager
import com.tianmeng.security.manager.SmartBlockManager
import com.tianmeng.security.manager.ShieldWatchdog
import com.tianmeng.security.service.AutoClickerService
import android.app.NotificationManager
import com.tianmeng.security.manager.MuteManager
import com.tianmeng.security.service.MuteGuardService
import com.tianmeng.security.service.AntiFlashService
import com.tianmeng.security.service.SecurityShieldService
import com.tianmeng.security.ui.AppListActivity
import com.tianmeng.security.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        /** 维护公告每个进程只提示一次 */
        private var maintenanceShown = false
    }

    private lateinit var tvShieldStatus: TextView
    private lateinit var btnToggleShield: MaterialButton
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvPermissionSummary: TextView
    private lateinit var btnRequestPermission: MaterialButton
    private lateinit var tvPrivilegeHint: TextView
    private lateinit var swShakeTrigger: MaterialSwitch
    private lateinit var swVolumeTrigger: MaterialSwitch
    private lateinit var swRootGuard: MaterialSwitch
    private lateinit var tvRootGuardHint: TextView
    private lateinit var llVolumeOptions: View
    private lateinit var llShakeOptions: View
    private lateinit var btnVolDown: TextView
    private lateinit var btnVolUp: TextView
    private lateinit var tvVolumeCount: TextView
    private lateinit var btnCountMinus: TextView
    private lateinit var btnCountPlus: TextView
    private lateinit var tvVolumeHint: TextView
    private lateinit var tvShakeHint: TextView
    private lateinit var levelViews: List<TextView>
    private lateinit var tvOverlayPermissionStatus: TextView
    private lateinit var dotOverlay: View
    private lateinit var rowOverlay: View
    private lateinit var rowOverlayPermission: View
    private lateinit var dotShizuku: View
    private lateinit var dotRoot: View
    private lateinit var dotAccessibility: View
    private lateinit var cardCrashLog: View
    private lateinit var tvCrashLog: TextView
    private lateinit var tvAdminStatus: TextView

    private lateinit var tabViews: List<TextView>
    private lateinit var panels: List<View>
    private lateinit var rgBlockMode: RadioGroup
    private lateinit var rbModes: List<MaterialRadioButton>
    private lateinit var swSmartBlock: MaterialSwitch
    private lateinit var tvSmartBlockStatus: TextView
    private var suppressSmartBlock = false
    private lateinit var swAntiFlash: MaterialSwitch
    private lateinit var tvAntiFlashStatus: TextView
    private var suppressAntiFlash = false
    private var pendingAntiFlash = false
    private lateinit var swKeepAlive: MaterialSwitch
    private lateinit var tvKeepAliveStatus: TextView
    private var suppressKeepAlive = false
    private lateinit var rowUsage: View
    private lateinit var dotUsage: View
    private lateinit var tvUsageStatus: TextView

    /** 三个模式选项对应的模式值：0 基础 / 1 强力 / 3 终结 */
    private val modeValues = listOf(0, 1, 3)
    private lateinit var tvModeDesc: TextView
    private lateinit var tvScanInfo: TextView
    private var suppressModeCallback = false

    /** 用户点击启动护盾但没有通知权限时，先申请再启动 */
    private var pendingStartAfterNotification = false

    /** 静音勿扰授权弹窗每个进程只提示一次 */
    private var muteDndPrompted = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingStartAfterNotification) {
                pendingStartAfterNotification = false
                startShield()
            }
            if (!granted) {
                // 没有通知权限也能运行前台服务，只是看不到常驻通知
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!pendingAntiFlash) return@registerForActivityResult
            pendingAntiFlash = false
            if (granted) {
                enableAntiFlash()
            } else {
                Prefs.antiFlash = false
                suppressAntiFlash = true
                swAntiFlash.isChecked = false
                suppressAntiFlash = false
                updateAntiFlashStatus()
                Toast.makeText(
                    this, R.string.anti_flash_denied, Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // 打开应用即自动恢复护盾（保证摇一摇 / 音量键立即可用）
        if (Prefs.shieldEnabled && !isServiceRunning()) {
            startShield()
        }

        updateShieldStatus()
        refreshStatusAsync()
        if (Prefs.antiFlash && hasCameraPermission() && !AntiFlashService.isRunning(this)) {
            AntiFlashService.start(this)
        }
        checkMaintenance()
    }

    /** 启动时读取远程维护公告，仅每个进程提示一次 */
    private fun checkMaintenance() {
        if (maintenanceShown) return
        lifecycleScope.launch {
            val notice = withContext(Dispatchers.IO) { MaintenanceManager.check() } ?: return@launch
            if (maintenanceShown || isFinishing || isDestroyed) return@launch
            maintenanceShown = true
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("系统维护")
                .setMessage(notice.message)
                .setPositiveButton("知道了") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 授权状态可能在外部（Shizuku / 系统设置）发生变化，回到界面时重新探测
        updateShieldStatus()
        refreshStatusAsync()
        updateRootGuardHint()
        refreshCrashLog()
        updateScanInfo()
        updateMuteStatus()
        updateAdminStatus()
        updateAntiFlashStatus()
        if (Prefs.antiFlash && hasCameraPermission() && !AntiFlashService.isRunning(this)) {
            AntiFlashService.start(this)
        }
        // 上次静音状态残留（服务被杀/进程重启）时自动把声音还回来
        MuteManager.recoverIfStale(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // 从系统设置返回时立即刷新本地权限状态，避免已开启却仍显示未授权
        refreshLocalPermissionRows()
    }

    private fun initViews() {
        tvShieldStatus = findViewById(R.id.tvShieldStatus)
        btnToggleShield = findViewById(R.id.btnToggleShield)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        btnRequestPermission = findViewById(R.id.btnRequestPermission)
        tvPrivilegeHint = findViewById(R.id.tvPrivilegeHint)
        swShakeTrigger = findViewById(R.id.swShakeTrigger)
        swVolumeTrigger = findViewById(R.id.swVolumeTrigger)
        swRootGuard = findViewById(R.id.swRootGuard)
        tvRootGuardHint = findViewById(R.id.tvRootGuardHint)
        llVolumeOptions = findViewById(R.id.llVolumeOptions)
        llShakeOptions = findViewById(R.id.llShakeOptions)
        btnVolDown = findViewById(R.id.btnVolDown)
        btnVolUp = findViewById(R.id.btnVolUp)
        tvVolumeCount = findViewById(R.id.tvVolumeCount)
        btnCountMinus = findViewById(R.id.btnCountMinus)
        btnCountPlus = findViewById(R.id.btnCountPlus)
        tvVolumeHint = findViewById(R.id.tvVolumeHint)
        tvShakeHint = findViewById(R.id.tvShakeHint)
        levelViews = listOf(
            findViewById(R.id.levelLight),
            findViewById(R.id.levelStandard),
            findViewById(R.id.levelStrong),
            findViewById(R.id.levelHeavy)
        )
        tvOverlayPermissionStatus = findViewById(R.id.tvOverlayPermissionStatus)
        dotOverlay = findViewById(R.id.dotOverlay)
        rowOverlay = findViewById(R.id.rowOverlay)
        rowOverlayPermission = findViewById(R.id.rowOverlayPermission)
        dotShizuku = findViewById(R.id.dotShizuku)
        dotRoot = findViewById(R.id.dotRoot)
        dotAccessibility = findViewById(R.id.dotAccessibility)
        cardCrashLog = findViewById(R.id.cardCrashLog)
        tvCrashLog = findViewById(R.id.tvCrashLog)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)

        tabViews = listOf(
            findViewById(R.id.tabHome),
            findViewById(R.id.tabTrigger),
            findViewById(R.id.tabScan)
        )
        panels = listOf(
            findViewById(R.id.panelHome),
            findViewById(R.id.panelTrigger),
            findViewById(R.id.panelScan)
        )

        rgBlockMode = findViewById(R.id.rgBlockMode)
        rbModes = listOf(
            findViewById(R.id.rbModeBasic),
            findViewById(R.id.rbModeStrong),
            findViewById(R.id.rbModeTerminal)
        )
        tvModeDesc = findViewById(R.id.tvModeDesc)
        tvScanInfo = findViewById(R.id.tvScanInfo)
        swSmartBlock = findViewById(R.id.swSmartBlock)
        tvSmartBlockStatus = findViewById(R.id.tvSmartBlockStatus)
        swSmartBlock.isChecked = Prefs.smartBlock
        swAntiFlash = findViewById(R.id.swAntiFlash)
        tvAntiFlashStatus = findViewById(R.id.tvAntiFlashStatus)
        swAntiFlash.isChecked = Prefs.antiFlash
        swKeepAlive = findViewById(R.id.swKeepAlive)
        tvKeepAliveStatus = findViewById(R.id.tvKeepAliveStatus)
        swKeepAlive.isChecked = Prefs.keepAlive
        rowUsage = findViewById(R.id.rowUsage)
        dotUsage = findViewById(R.id.dotUsage)
        tvUsageStatus = findViewById(R.id.tvUsageStatus)

        // 恢复设置（先赋值，再挂监听，避免回调误触发）
        swShakeTrigger.isChecked = Prefs.shakeTrigger
        swVolumeTrigger.isChecked = Prefs.volumeTrigger
        swRootGuard.isChecked = Prefs.rootGuard
        updateRootGuardHint()
        updateVolumeKeyStyles()
        updateVolumeCountView()
        updateShakeLevelStyles()
        updateTriggerOptionVisibility()

        // 恢复拦截模式（旧的守护模式 2 已删除，归入强力模式 1）
        suppressModeCallback = true
        var savedMode = Prefs.blockMode.let { if (it == 2) 1 else it }
        if (savedMode !in modeValues) savedMode = 1
        Prefs.blockMode = savedMode
        rbModes[modeValues.indexOf(savedMode)].isChecked = true
        suppressModeCallback = false
        updateModeStyles(savedMode)

        switchTab(0)
    }

    private fun switchTab(index: Int) {
        tabViews.forEachIndexed { i, tab ->
            val selected = i == index
            tab.setBackgroundResource(
                if (selected) R.drawable.bg_segment_selected else 0
            )
            tab.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.text_secondary)
            )
            tab.typeface = if (selected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        if (index == 2) updateScanInfo()
    }

    private fun updateTriggerOptionVisibility() {
        llVolumeOptions.alpha = if (Prefs.volumeTrigger) 1f else 0.4f
        llShakeOptions.alpha = if (Prefs.shakeTrigger) 1f else 0.4f
    }

    private fun updateVolumeKeyStyles() {
        val downSelected = Prefs.volumeKey == 0
        applyOptionStyle(btnVolDown, downSelected)
        applyOptionStyle(btnVolUp, !downSelected)
    }

    private fun applyOptionStyle(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_mode_item_selected else R.drawable.bg_mode_item
        )
        view.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.white else R.color.text_secondary)
        )
        view.setTypeface(
            null,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }

    private fun updateVolumeCountView() {
        val count = Prefs.volumePressCount
        tvVolumeCount.text = count.toString()
        val keyName = getString(
            if (Prefs.volumeKey == 1) R.string.trigger_key_volume_up
            else R.string.trigger_key_volume_down
        )
        tvVolumeHint.text = getString(R.string.trigger_hint, count, keyName)
        btnCountMinus.alpha = if (count <= 1) 0.4f else 1f
        btnCountPlus.alpha = if (count >= 3) 0.4f else 1f
    }

    private fun updateShakeLevelStyles() {
        val level = Prefs.shakeLevel
        levelViews.forEachIndexed { index, view ->
            applyOptionStyle(view, index == level)
        }
        val levelName = getString(
            when (level) {
                0 -> R.string.shake_level_light
                1 -> R.string.shake_level_standard
                2 -> R.string.shake_level_strong
                else -> R.string.shake_level_heavy
            }
        )
        tvShakeHint.text = getString(R.string.shake_hint, levelName)
    }

    private fun requestOverlayPermission() {
        if (PermissionManager.isOverlayGranted(this)) {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
            refreshLocalPermissionRows()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Throwable) {
                // ignore
            }
        }
    }

    private fun updateModeStyles(mode: Int) {
        rbModes.forEachIndexed { i, rb ->
            val selected = modeValues[i] == mode
            rb.setBackgroundResource(
                if (selected) R.drawable.bg_mode_item_selected else R.drawable.bg_mode_item
            )
            rb.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.white else R.color.text_primary)
            )
        }
        tvModeDesc.setText(
            when (mode) {
                0 -> R.string.mode_basic_desc
                3 -> R.string.mode_terminal_desc
                else -> R.string.mode_strong_desc
            }
        )
        updateScanInfo()
    }

    private fun updateRootGuardHint() {
        tvRootGuardHint.text = when {
            Prefs.rootGuard && ShellManager.currentMode == ShellManager.Mode.ROOT ->
                "防护中：格机文件 / 炸内存 / 相册批量删除"
            Prefs.rootGuard ->
                "炸内存防护中（格机文件拦截需 Root）"
            else -> "炸内存免 Root；格机文件拦截需 Root"
        }
    }

    private fun modeName(mode: Int): String = when (mode) {
        0 -> getString(R.string.mode_basic)
        3 -> getString(R.string.mode_terminal)
        else -> getString(R.string.mode_strong)
    }

    private fun updateScanInfo() {
        tvScanInfo.text = "当前拦截模式：${modeName(Prefs.blockMode)}"
    }

    private fun setupListeners() {
        btnToggleShield.setOnClickListener {
            if (isServiceRunning()) {
                Prefs.shieldEnabled = false
                ShieldWatchdog.disarm(this)
                SecurityShieldService.stop(this)
                updateShieldStatus()
            } else {
                Prefs.shieldEnabled = true
                ensureNotificationPermissionThenStart()
            }
        }

        btnRequestPermission.setOnClickListener { requestAllPermissions() }

        swShakeTrigger.setOnCheckedChangeListener { _, isChecked ->
            Prefs.shakeTrigger = isChecked
            updateTriggerOptionVisibility()
            if (isServiceRunning()) {
                SecurityShieldService.stop(this)
                SecurityShieldService.start(this)
                updateShieldStatus()
            }
        }

        swVolumeTrigger.setOnCheckedChangeListener { _, isChecked ->
            Prefs.volumeTrigger = isChecked
            updateTriggerOptionVisibility()
        }

        btnVolDown.setOnClickListener {
            Prefs.volumeKey = 0
            updateVolumeKeyStyles()
            updateVolumeCountView()
        }
        btnVolUp.setOnClickListener {
            Prefs.volumeKey = 1
            updateVolumeKeyStyles()
            updateVolumeCountView()
        }
        btnCountMinus.setOnClickListener {
            Prefs.volumePressCount = Prefs.volumePressCount - 1
            updateVolumeCountView()
        }
        btnCountPlus.setOnClickListener {
            Prefs.volumePressCount = Prefs.volumePressCount + 1
            updateVolumeCountView()
        }
        levelViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                Prefs.shakeLevel = index
                updateShakeLevelStyles()
            }
        }

        swRootGuard.setOnCheckedChangeListener { _, isChecked ->
            Prefs.rootGuard = isChecked
            if (isChecked) {
                RootGuard.start(this)
                if (ShellManager.currentMode != ShellManager.Mode.ROOT) {
                    Toast.makeText(this, "已开启炸内存防护；格机文件拦截需要 Root 权限", Toast.LENGTH_LONG).show()
                }
            } else {
                RootGuard.stop()
            }
            updateRootGuardHint()
        }

        swSmartBlock.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSmartBlock) return@setOnCheckedChangeListener
            if (!isChecked) {
                Prefs.smartBlock = false
                SmartBlockManager.clearCooldown()
                Toast.makeText(this, R.string.smart_block_off_toast, Toast.LENGTH_SHORT).show()
                updateSmartBlockStatus()
                return@setOnCheckedChangeListener
            }
            // 开启前必须确认 Root/Shizuku + 无障碍 + 悬浮窗全部就绪
            lifecycleScope.launch {
                val issues = withContext(Dispatchers.IO) {
                    ShellManager.probeMode()
                    SmartBlockManager.gateIssues(this@MainActivity)
                }
                if (issues.isEmpty()) {
                    Prefs.smartBlock = true
                    Toast.makeText(
                        this@MainActivity, R.string.smart_block_on_toast, Toast.LENGTH_SHORT
                    ).show()
                    if (!isServiceRunning()) {
                        SecurityShieldService.start(this@MainActivity)
                        Prefs.shieldEnabled = true
                    }
                    SmartBlockManager.scanNow(this@MainActivity) { removed ->
                        val msg = if (removed > 0) {
                            getString(R.string.smart_block_scan_done, removed)
                        } else {
                            getString(R.string.smart_block_scan_none)
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        updateSmartBlockStatus()
                    }
                } else {
                    Prefs.smartBlock = false
                    suppressSmartBlock = true
                    swSmartBlock.isChecked = false
                    suppressSmartBlock = false
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.smart_block_gate_title)
                        .setMessage(
                            getString(
                                R.string.smart_block_gate_msg,
                                issues.joinToString("\n• ", prefix = "• ")
                            )
                        )
                        .setPositiveButton("去开启") { dialog, _ ->
                            dialog.dismiss()
                            requestAllPermissions()
                        }
                        .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
                updateSmartBlockStatus()
            }
        }

        swAntiFlash.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAntiFlash) return@setOnCheckedChangeListener
            if (!isChecked) {
                Prefs.antiFlash = false
                AntiFlashService.stop(this)
                updateAntiFlashStatus()
                return@setOnCheckedChangeListener
            }
            if (hasCameraPermission()) {
                enableAntiFlash()
            } else {
                pendingAntiFlash = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        rowOverlay.setOnClickListener { requestOverlayPermission() }
        rowOverlayPermission.setOnClickListener { requestOverlayPermission() }
        rowUsage.setOnClickListener {
            if (PermissionManager.hasUsageAccess(this)) {
                Toast.makeText(this, "使用情况访问已开启", Toast.LENGTH_SHORT).show()
                refreshLocalPermissionRows()
            } else {
                PermissionManager.openUsageAccessSettings(this)
            }
        }

        swKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            if (suppressKeepAlive) return@setOnCheckedChangeListener
            if (!isChecked) {
                KeepAliveManager.disable(this)
                Toast.makeText(this, R.string.keep_alive_off, Toast.LENGTH_SHORT).show()
                updateKeepAliveStatus()
                return@setOnCheckedChangeListener
            }
            enableKeepAliveWithPermissions()
        }
        findViewById<View>(R.id.cardKeepAlive).setOnClickListener {
            if (!Prefs.keepAlive) {
                suppressKeepAlive = true
                swKeepAlive.isChecked = true
                suppressKeepAlive = false
            }
            enableKeepAliveWithPermissions()
        }

        findViewById<View>(R.id.cardVirusScan).setOnClickListener {
            openList(AppListActivity.MODE_VIRUS)
        }
        findViewById<View>(R.id.cardAppFreeze).setOnClickListener {
            openList(AppListActivity.MODE_FREEZE)
        }
        findViewById<View>(R.id.cardFrozenList).setOnClickListener {
            openList(AppListActivity.MODE_FROZEN)
        }

        findViewById<View>(R.id.cardTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        findViewById<View>(R.id.cardAutoClicker).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.island_need_overlay, Toast.LENGTH_SHORT).show()
                requestOverlayPermission()
                return@setOnClickListener
            }
            if (!PermissionManager.isAccessibilityEnabled(this) &&
                ShellManager.currentMode == ShellManager.Mode.NONE
            ) {
                Toast.makeText(this, R.string.island_need_capability, Toast.LENGTH_SHORT).show()
            }
            AutoClickerService.start(this)
            Toast.makeText(this, R.string.island_notification_text, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.cardMute).setOnClickListener {
            if (MuteManager.isEnabled()) {
                // 先直接恢复声音，再停服务：即使服务已被系统杀掉，也能还原音量和响铃模式
                MuteManager.disable(this)
                MuteGuardService.stop(this)
                Toast.makeText(this, R.string.mute_disabled_toast, Toast.LENGTH_SHORT).show()
            } else {
                MuteGuardService.start(this)
                Toast.makeText(this, R.string.mute_enabled_toast, Toast.LENGTH_SHORT).show()
                maybeOfferDndAccess()
            }
            window.decorView.postDelayed({ updateMuteStatus() }, 300)
        }

        findViewById<View>(R.id.cardWhitelist).setOnClickListener {
            openList(AppListActivity.MODE_WHITELIST)
        }

        findViewById<View>(R.id.cardDeviceAdmin).setOnClickListener {
            toggleDeviceAdmin()
        }

        findViewById<View>(R.id.cardFeedback).setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnClearCrash).setOnClickListener {
            CrashLogger.clear(this)
            cardCrashLog.visibility = View.GONE
        }

        // 顶部标签切换
        tabViews.forEachIndexed { index, tab ->
            tab.setOnClickListener { switchTab(index) }
        }

        // 拦截模式切换
        rgBlockMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressModeCallback) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbModeBasic -> 0
                R.id.rbModeTerminal -> 3
                else -> 1
            }
            Prefs.blockMode = mode
            updateModeStyles(mode)
        }

        // 扫描页按钮
        findViewById<MaterialButton>(R.id.btnStartScan).setOnClickListener {
            openList(AppListActivity.MODE_VIRUS)
        }
    }

    private fun openList(mode: String) {
        startActivity(Intent(this, AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_MODE, mode)
        })
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterNotification = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startShield()
        }
    }

    private fun startShield() {
        try {
            SecurityShieldService.start(this)
            Prefs.shieldEnabled = true
        } catch (e: Throwable) {
            // 启动失败时不影响界面
        }
        updateShieldStatus()
    }

    /**
     * 后台线程做权限探测（su / Shizuku binder 都不能放主线程），完成后回主线程刷新界面。
     */
    private fun refreshStatusAsync() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                ShellManager.awaitReady(2000)
                // 已确认 Root 时跳过重复 su 探测；否则同步探测一次，避免 async redetect 未完成时显示旧状态
                val mode = if (ShellManager.currentMode == ShellManager.Mode.ROOT) {
                    ShellManager.currentMode
                } else {
                    ShellManager.probeMode()
                }
                val shizukuGranted = try {
                    PermissionManager.isShizukuGranted()
                } catch (e: Throwable) {
                    false
                }
                val shizukuAvailable = mode == ShellManager.Mode.SHIZUKU ||
                        try {
                            PermissionManager.isShizukuAvailable()
                        } catch (e: Throwable) {
                            false
                        }
                val root = mode == ShellManager.Mode.ROOT
                val accessibility = PermissionManager.isAccessibilityEnabled(this@MainActivity)
                val overlay = PermissionManager.isOverlayGranted(this@MainActivity)
                val usage = PermissionManager.hasUsageAccess(this@MainActivity)
                PermissionState(
                    shizukuGranted, shizukuAvailable, root, accessibility, overlay, usage
                )
            }
            renderPermissionState(state)
            updateAdminStatus()
            updateShieldStatus()
            updateSmartBlockStatus()
            updateKeepAliveStatus()
        }
    }

    /** 只刷新无需后台探测的本地权限项（无障碍 / 悬浮窗），修复“已开启却显示未开启” */
    private fun refreshLocalPermissionRows() {
        val accessibility = PermissionManager.isAccessibilityEnabled(this)
        val overlay = PermissionManager.isOverlayGranted(this)
        val usage = PermissionManager.hasUsageAccess(this)
        if (accessibility) {
            setRow(tvAccessibilityStatus, dotAccessibility, R.string.granted, RowState.OK)
        } else {
            setRow(tvAccessibilityStatus, dotAccessibility, R.string.not_granted, RowState.BAD)
        }
        if (overlay) {
            setRow(tvOverlayPermissionStatus, dotOverlay, R.string.granted, RowState.OK)
        } else {
            setRow(tvOverlayPermissionStatus, dotOverlay, R.string.not_granted, RowState.BAD)
        }
        if (usage) {
            setRow(tvUsageStatus, dotUsage, R.string.granted, RowState.OK)
        } else {
            setRow(tvUsageStatus, dotUsage, R.string.not_granted, RowState.BAD)
        }
        updateSmartBlockStatus()
        updateKeepAliveStatus()
    }

    private fun updateKeepAliveStatus() {
        if (!Prefs.keepAlive) {
            tvKeepAliveStatus.text = if (KeepAliveManager.missingItems(this).isEmpty()) {
                "当前后台加固条件均已满足，开启后可防止划掉 / 冻结"
            } else {
                getString(
                    R.string.keep_alive_status_missing,
                    KeepAliveManager.missingItems(this).joinToString("、")
                )
            }
            tvKeepAliveStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            return
        }
        val missing = KeepAliveManager.missingItems(this)
        if (missing.isEmpty()) {
            tvKeepAliveStatus.text = getString(R.string.keep_alive_status_ready)
            tvKeepAliveStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvKeepAliveStatus.text = getString(
                R.string.keep_alive_status_missing, missing.joinToString("、")
            )
            tvKeepAliveStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
        }
    }

    /**
     * 开启防冻结：启动后台常驻，并引导授予使用情况访问与电池优化白名单。
     */
    private fun enableKeepAliveWithPermissions() {
        KeepAliveManager.enable(this)
        updateShieldStatus()
        val usage = PermissionManager.hasUsageAccess(this)
        val battery = PermissionManager.isIgnoringBatteryOptimizations(this)
        when {
            !usage -> {
                Toast.makeText(this, R.string.keep_alive_need_usage, Toast.LENGTH_LONG).show()
                PermissionManager.openUsageAccessSettings(this)
            }
            !battery -> {
                Toast.makeText(this, R.string.keep_alive_need_battery, Toast.LENGTH_LONG).show()
                PermissionManager.requestIgnoreBatteryOptimizations(this)
            }
            else -> {
                Toast.makeText(this, R.string.keep_alive_on, Toast.LENGTH_SHORT).show()
            }
        }
        updateKeepAliveStatus()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableAntiFlash() {
        Prefs.antiFlash = true
        AntiFlashService.start(this)
        if (!swAntiFlash.isChecked) {
            suppressAntiFlash = true
            swAntiFlash.isChecked = true
            suppressAntiFlash = false
        }
        updateAntiFlashStatus()
        Toast.makeText(this, R.string.anti_flash_enabled, Toast.LENGTH_SHORT).show()
    }

    private fun updateAntiFlashStatus() {
        val on = Prefs.antiFlash
        when {
            !on -> {
                tvAntiFlashStatus.text = getString(R.string.anti_flash_off)
                tvAntiFlashStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            !hasCameraPermission() -> {
                tvAntiFlashStatus.text = getString(R.string.anti_flash_need_camera)
                tvAntiFlashStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
            else -> {
                tvAntiFlashStatus.text = getString(R.string.anti_flash_on)
                tvAntiFlashStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
        }
    }

    private fun updateSmartBlockStatus() {
        val issues = SmartBlockManager.gateIssues(this)
        val privileged = when (ShellManager.currentMode) {
            ShellManager.Mode.ROOT -> "Root"
            ShellManager.Mode.SHIZUKU -> "Shizuku"
            else -> "Root / Shizuku"
        }
        if (issues.isEmpty()) {
            tvSmartBlockStatus.text = getString(R.string.smart_block_status_ready, privileged)
            tvSmartBlockStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvSmartBlockStatus.text = getString(
                R.string.smart_block_status_missing, issues.joinToString("、")
            )
            tvSmartBlockStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
        }
    }

    private fun renderPermissionState(state: PermissionState) {
        // Shizuku
        when {
            state.root -> setRow(tvShizukuStatus, dotShizuku, "无需（已 Root）", RowState.OK)
            state.shizukuGranted -> setRow(tvShizukuStatus, dotShizuku, R.string.granted, RowState.OK)
            state.shizukuAvailable -> setRow(tvShizukuStatus, dotShizuku, "待授权", RowState.PENDING)
            else -> setRow(tvShizukuStatus, dotShizuku, R.string.not_granted, RowState.BAD)
        }

        // Root
        if (state.root) {
            setRow(tvRootStatus, dotRoot, R.string.granted, RowState.OK)
        } else {
            setRow(tvRootStatus, dotRoot, R.string.not_granted, RowState.BAD)
        }

        // 无障碍
        if (state.accessibility) {
            setRow(tvAccessibilityStatus, dotAccessibility, R.string.granted, RowState.OK)
        } else {
            setRow(tvAccessibilityStatus, dotAccessibility, R.string.not_granted, RowState.BAD)
        }

        // 悬浮窗
        if (state.overlay) {
            setRow(tvOverlayPermissionStatus, dotOverlay, R.string.granted, RowState.OK)
        } else {
            setRow(tvOverlayPermissionStatus, dotOverlay, R.string.not_granted, RowState.BAD)
        }

        // 使用情况访问
        if (state.usage) {
            setRow(tvUsageStatus, dotUsage, R.string.granted, RowState.OK)
        } else {
            setRow(tvUsageStatus, dotUsage, R.string.not_granted, RowState.BAD)
        }

        // 执行通道说明：优先 Root，没有 Root 时必须完成 Shizuku 授权
        tvPrivilegeHint.text = when {
            state.root -> getString(R.string.privilege_mode_root)
            state.shizukuGranted -> getString(R.string.privilege_mode_shizuku)
            ShellManager.guardInterceptorPresent -> getString(R.string.privilege_mode_guard_need_auth)
            else -> getString(R.string.privilege_mode_none)
        }

        // 统计三项必备能力：Root 或 Shizuku、无障碍、悬浮窗
        val privileged = state.root || state.shizukuGranted
        val count = (if (privileged) 1 else 0) +
                (if (state.accessibility) 1 else 0) +
                (if (state.overlay) 1 else 0)
        tvPermissionSummary.text = "$count / 3"
        tvPermissionSummary.setBackgroundResource(
            if (count >= 3) R.drawable.bg_pill_success else R.drawable.bg_pill
        )
        tvPermissionSummary.setTextColor(
            ContextCompat.getColor(
                this,
                if (count >= 3) R.color.success else R.color.text_secondary
            )
        )
    }

    private fun setRow(tv: TextView, dot: View, textRes: Int, state: RowState) {
        setRow(tv, dot, getString(textRes), state)
    }

    private fun setRow(tv: TextView, dot: View, text: String, state: RowState) {
        val colorRes = when (state) {
            RowState.OK -> R.color.success
            RowState.PENDING -> R.color.warning
            RowState.BAD -> R.color.danger
        }
        val pillRes = when (state) {
            RowState.OK -> R.drawable.bg_pill_success
            RowState.PENDING -> R.drawable.bg_pill_warning
            RowState.BAD -> R.drawable.bg_pill_danger
        }
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
        tv.setBackgroundResource(pillRes)
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun requestAllPermissions() {
        // 必须完成 Shizuku 授权，不再跳过（无线调试启动的 Shizuku 同样走这条授权）
        if (!PermissionManager.isShizukuGranted() && ShellManager.currentMode != ShellManager.Mode.ROOT) {
            if (PermissionManager.isShizukuAvailable()) {
                PermissionManager.requestShizukuPermission(this)
            } else {
                PermissionManager.openShizukuApp(this)
            }
        }

        val accessibilityMissing = !PermissionManager.isAccessibilityEnabled(this)
        val usageMissing = !PermissionManager.hasUsageAccess(this)
        if (accessibilityMissing) {
            // 一次只跳一个系统设置页，用户返回后可再次点击申请悬浮窗
            PermissionManager.openAccessibilitySettings(this)
        } else if (usageMissing) {
            PermissionManager.openUsageAccessSettings(this)
        } else {
            requestOverlayPermission()
        }
    }

    /**
     * 判断护盾前台服务是否在运行（只查询本应用自己的服务，无需权限）
     */
    private fun isServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == SecurityShieldService::class.java.name
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun updateShieldStatus() {
        val running = isServiceRunning()
        if (running) {
            tvShieldStatus.text = getString(R.string.shield_running)
            tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            btnToggleShield.text = getString(R.string.stop_shield)
            btnToggleShield.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
        } else {
            tvShieldStatus.text = getString(R.string.shield_stopped)
            tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
            btnToggleShield.text = getString(R.string.start_shield)
            btnToggleShield.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        }
        applyModeAvailability(running)
    }

    /**
     * 强力 / 终结模式依赖实时守护：护盾未开启时禁用，并回落到基础模式
     */
    private fun applyModeAvailability(guardOn: Boolean) {
        rbModes.forEachIndexed { i, rb ->
            if (modeValues[i] == 0) return@forEachIndexed
            rb.isEnabled = guardOn
            rb.alpha = if (guardOn) 1f else 0.45f
        }
        // 用户主动关闭实时守护时才回落基础模式；避免启动瞬间服务尚未就绪被误降级
        if (!guardOn && !Prefs.shieldEnabled && Prefs.blockMode != 0) {
            Prefs.blockMode = 0
            suppressModeCallback = true
            rbModes[0].isChecked = true
            suppressModeCallback = false
            updateModeStyles(0)
            Toast.makeText(this, "开启实时守护后才能使用强力 / 终结模式，已切回基础模式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAdminStatus() {
        val root = AdminManager.hasRoot()
        val active = AdminManager.isActive(this)
        findViewById<View>(R.id.cardDeviceAdmin).alpha = if (root || active) 1f else 0.5f
        tvAdminStatus.text = when {
            active -> getString(R.string.admin_active)
            root -> getString(R.string.admin_inactive)
            else -> getString(R.string.admin_need_root)
        }
        tvAdminStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.success else R.color.text_secondary
            )
        )
    }

    private fun updateMuteStatus() {
        val on = MuteManager.isEnabled()
        val tv = findViewById<TextView>(R.id.tvMuteStatus)
        tv.setText(if (on) R.string.mute_guard_on else R.string.mute_guard_off)
        tv.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.success else R.color.text_secondary)
        )
    }

    /** 未授予勿扰访问时提示一次，授权后连闹钟也能静音 */
    private fun maybeOfferDndAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.isNotificationPolicyAccessGranted || muteDndPrompted) return
        muteDndPrompted = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mute_dnd_title)
            .setMessage(R.string.mute_dnd_msg)
            .setPositiveButton(R.string.mute_dnd_go) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Throwable) {
                    // 部分 ROM 没有该入口，忽略
                }
            }
            .setNegativeButton(R.string.mute_dnd_skip, null)
            .show()
    }

    /**
     * 点击设备管理器卡片：未激活时跳系统激活页，已激活时询问是否取消。
     * 设备管理器按用户要求仅 Root 可用。
     */
    private fun toggleDeviceAdmin() {
        if (AdminManager.isActive(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.admin_deactivate_title)
                .setMessage(R.string.admin_deactivate_msg)
                .setPositiveButton(R.string.admin_deactivate_confirm) { _, _ ->
                    AdminManager.deactivate(this)
                    updateAdminStatus()
                }
                .setNegativeButton(R.string.admin_keep, null)
                .show()
            return
        }
        if (!AdminManager.hasRoot()) {
            Toast.makeText(this, R.string.admin_need_root, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(AdminManager.requestIntent(this))
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.admin_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCrashLog() {
        val log = CrashLogger.readLastCrash(this)
        if (log.isNullOrBlank()) {
            cardCrashLog.visibility = View.GONE
        } else {
            cardCrashLog.visibility = View.VISIBLE
            tvCrashLog.text = log
        }
    }

    private data class PermissionState(
        val shizukuGranted: Boolean,
        val shizukuAvailable: Boolean,
        val root: Boolean,
        val accessibility: Boolean,
        val overlay: Boolean,
        val usage: Boolean
    )

    private enum class RowState { OK, PENDING, BAD }
}
