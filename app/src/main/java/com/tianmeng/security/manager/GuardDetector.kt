package com.tianmeng.security.manager

import android.util.Log
import com.tianmeng.security.App

/**
 * 检测设备上是否装有「游龙」这类会拦截 shell 命令、并要求自身授权的安全工具。
 *
 * 检测到后，本应用会在没有 Root 的情况下主动跳过 Shizuku：
 * 不发送任何 shell 命令，也就不会被游龙拦截、更不会弹出游龙的授权/确认框，
 * 改用系统级 killBackgroundProcesses + 系统卸载器完成清理。
 */
object GuardDetector {

    private const val TAG = "GuardDetector"

    @Volatile
    private var cached: Boolean? = null

    fun hasYoulong(): Boolean {
        cached?.let { return it }
        val result = try {
            val context = App.instance
            val pm = context.packageManager
            pm.getInstalledApplications(0).any { info ->
                val pkg = info.packageName.lowercase()
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    ""
                }
                pkg.contains("youlong") || pkg.contains("youlo") ||
                        label.contains("游龙") || label.contains("YouLong", ignoreCase = true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Detect guard app failed", e)
            false
        }
        cached = result
        Log.d(TAG, "Youlong-like guard present: $result")
        return result
    }
}
