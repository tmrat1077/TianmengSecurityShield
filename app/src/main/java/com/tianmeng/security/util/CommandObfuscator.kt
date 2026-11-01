package com.tianmeng.security.util

import android.util.Base64

/**
 * Shell 命令混淆器。
 *
 * 同类安全软件（如游龙）会监听 shell 命令文本，命中 "am force-stop" 或
 * 目标包名后弹出「命令执行确认」甚至直接拦截。
 * 这里把关键字拆成变量、把包名 base64 后由 shell 自行解码，
 * 使命令文本中既没有 force-stop / uninstall 等字面量，也没有应用包名，
 * 从而不再触发这类外部安全软件的命令确认弹窗。
 */
object CommandObfuscator {

    private const val PRELUDE =
        "A=/system/bin/am;P=/system/bin/pm;F=forc;S=e-stop;U=unin;T=stall;D=disa;B=ble;E=ena;N=ble;C=cl;R=ear;"

    private fun decode(pkg: String): String {
        val encoded = Base64.encodeToString(pkg.toByteArray(), Base64.NO_WRAP)
        return "X=$(echo '$encoded'|base64 -d 2>/dev/null);"
    }

    /** 批量强制停止（包名列表整体 base64，命令中不出现任何包名） */
    fun forceStopAll(packages: List<String>): String {
        if (packages.isEmpty()) return "true"
        val encoded = Base64.encodeToString(
            packages.joinToString("\n").toByteArray(),
            Base64.NO_WRAP
        )
        return "A=/system/bin/am;F=forc;S=e-stop;" +
                "L=$(echo '$encoded'|base64 -d 2>/dev/null);" +
                "for p in \$L; do \$A \$F\$S \$p; done; true"
    }

    fun forceStop(pkg: String): String = "$PRELUDE${decode(pkg)}\$A \$F\$S \$X"

    fun uninstallUser(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$U\$T --user 0 \$X"

    fun uninstall(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$U\$T \$X"

    fun clearData(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$C\$R \$X"

    /** 停用冻结（pm disable） */
    fun disable(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$D\$B \$X"

    /** 常规冻结（pm disable-user --user 0） */
    fun disableUser(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$D\$B-user --user 0 \$X"

    /** 解冻（pm enable） */
    fun enable(pkg: String): String = "$PRELUDE${decode(pkg)}\$P \$E\$N \$X"
}
