package com.tianmeng.security.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.SmartBlockManager

/**
 * 监听新安装 / 更新：开启智能拦截时立即判定并自动卸载真实病毒。
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.smartBlock) return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        SmartBlockManager.onPackageChanged(context.applicationContext, pkg)
    }
}
