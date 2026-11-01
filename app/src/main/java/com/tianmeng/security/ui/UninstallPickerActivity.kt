package com.tianmeng.security.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tianmeng.security.R
import android.content.pm.PackageManager
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.ShellManager
import com.tianmeng.security.util.AppUtils
import com.tianmeng.security.util.LockScreenUi
import com.tianmeng.security.util.RiskyAppScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 紧急卸载页：
 * 列出第三方应用（带无障碍 / 悬浮窗 / 设备管理器的高危应用排在最前），
 * 可多选后一键静默卸载。
 */
class UninstallPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRESELECT = "extra_preselect"
        private const val TAG = "UninstallPicker"

        fun createIntent(context: Context, preselected: List<String>): Intent {
            return Intent(context, UninstallPickerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
                putStringArrayListExtra(EXTRA_PRESELECT, ArrayList(preselected))
            }
        }
    }

    private lateinit var llList: LinearLayout
    private lateinit var tvEmpty: TextView
    private val selected = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uninstall_picker)
        LockScreenUi.makeFullScreen(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        llList = findViewById(R.id.llUninstallList)
        tvEmpty = findViewById(R.id.tvUninstallEmpty)

        val preselected = intent.getStringArrayListExtra(EXTRA_PRESELECT) ?: arrayListOf()
        selected.addAll(preselected)

        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "正在加载应用列表…"

        findViewById<View>(R.id.btnUninstallClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnUninstallConfirm).setOnClickListener { confirmUninstall() }

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                RiskyAppScanner.scan(this@UninstallPickerActivity)
            }
            render(apps)
        }
        // 紧急页展示期间持续压制其他悬浮窗（锁机病毒常用悬浮窗盖住卸载页），保证页面始终置顶
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                suppressOverlayApps()
                delay(2000L)
            }
        }
    }

    /** 强制停止除白名单外的所有申请了悬浮窗权限的第三方应用，防止其悬浮窗覆盖本页 */
    private fun suppressOverlayApps() {
        try {
            val pm = packageManager
            val whitelist = Prefs.getWhitelist()
            AppUtils.getInstalledApps(this).forEach { app ->
                val pkg = app.packageName
                if (pkg == packageName || app.isSystemApp || whitelist.contains(pkg)) return@forEach
                val perms = try {
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.toList() ?: emptyList()
                } catch (e: Throwable) {
                    emptyList()
                }
                if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) {
                    ShellManager.forceStop(pkg)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Suppress overlay apps failed", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LockScreenUi.hideSystemBars(this)
    }

    private fun render(apps: List<RiskyAppScanner.RiskyApp>) {
        llList.removeAllViews()
        if (apps.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.uninstall_empty)
            return
        }
        tvEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        apps.forEach { app ->
            val item = inflater.inflate(R.layout.item_uninstall, llList, false)
            item.findViewById<TextView>(R.id.tvUninstallName).text = app.appName
            item.findViewById<TextView>(R.id.tvUninstallPkg).text = app.packageName

            val flags = mutableListOf<String>()
            if (app.isSystem) flags.add(getString(R.string.flag_system))
            if (app.hasAccessibility) flags.add(getString(R.string.flag_accessibility))
            if (app.hasOverlay) flags.add(getString(R.string.flag_overlay))
            if (app.hasAdmin) flags.add(getString(R.string.flag_admin))
            item.findViewById<TextView>(R.id.tvUninstallFlags).text = flags.joinToString("\n")

            val cb = item.findViewById<CheckBox>(R.id.cbUninstall)
            cb.isChecked = selected.contains(app.packageName)
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(app.packageName)
                else selected.remove(app.packageName)
            }
            item.setOnClickListener { cb.isChecked = !cb.isChecked }

            llList.addView(item)
        }
    }

    private fun confirmUninstall() {
        if (selected.isEmpty()) {
            toast(getString(R.string.uninstall_none_selected))
            return
        }
        // 全屏确认弹窗：铺满屏幕、显示在锁屏之上，避免被锁机界面遮挡
        val dialog = Dialog(this, R.style.Theme_Tianmeng_FullscreenDialog)
        dialog.setContentView(R.layout.dialog_confirm_fullscreen)
        dialog.findViewById<TextView>(R.id.tvDialogTitle).text =
            getString(R.string.uninstall_title)
        dialog.findViewById<TextView>(R.id.tvDialogMessage).text =
            getString(R.string.uninstall_confirm_dialog, selected.size)
        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<View>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.btnDialogConfirm).setOnClickListener {
            dialog.dismiss()
            doUninstall()
        }
        dialog.show()
        LockScreenUi.makeDialogFullScreen(dialog)
    }

    private fun doUninstall() {
        val targets = selected.toList()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { uninstallAll(targets) }
            if (result.first >= 0) {
                toast(getString(R.string.uninstall_done, result.first, result.second))
            }
            finish()
        }
    }

    private fun uninstallAll(targets: List<String>): Pair<Int, Int> {
        ShellManager.awaitReady(1500)
        val hasPrivilege = ShellManager.currentMode != ShellManager.Mode.NONE

        if (!hasPrivilege) {
            // 无特权：逐个打开系统卸载确认框
            runOnUiThread {
                toast(getString(R.string.uninstall_fallback))
                targets.forEach { pkg ->
                    try {
                        val intent = Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:$pkg")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Open delete intent failed: $pkg", e)
                    }
                }
            }
            return -1 to 0
        }

        var success = 0
        var failed = 0
        targets.forEach { pkg ->
            if (uninstallOne(pkg)) success++ else failed++
        }
        return success to failed
    }

    private fun uninstallOne(pkg: String): Boolean {
        return try {
            var result = ShellManager.uninstallApp(pkg)
            if (!result.isSuccess) {
                // 部分 ROM 不支持 --user 0，回退普通卸载
                result = ShellManager.uninstallPlain(pkg)
            }
            result.isSuccess
        } catch (e: Throwable) {
            Log.e(TAG, "Uninstall $pkg failed", e)
            false
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
