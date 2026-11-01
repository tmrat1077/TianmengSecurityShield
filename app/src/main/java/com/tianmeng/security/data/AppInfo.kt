package com.tianmeng.security.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val isFrozen: Boolean = false,
    val freezeMode: FreezeMode = FreezeMode.NONE,
    val isVirus: Boolean = false,
    val virusReason: String = ""
)

enum class FreezeMode {
    NONE,       // 未冻结
    NORMAL,     // 常规冻结：隐藏桌面图标
    DISABLED    // 停用冻结：点击显示接管提示
}
