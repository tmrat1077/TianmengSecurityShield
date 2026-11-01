package com.tianmeng.security.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志记录：把未捕获异常写入私有目录，下次启动时可在界面里查看，
 * 方便定位闪退原因（无需 adb logcat）。
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.log"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    append("时间：")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                    append("\n线程：")
                    append(thread.name)
                    append("\n设备：")
                    append(android.os.Build.MANUFACTURER)
                    append(" ")
                    append(android.os.Build.MODEL)
                    append(" / Android ")
                    append(android.os.Build.VERSION.RELEASE)
                    append(" (API ")
                    append(android.os.Build.VERSION.SDK_INT)
                    append(")\n\n")
                    append(throwable.stackTraceToString())
                }
                File(appContext.filesDir, FILE_NAME).writeText(report)
            } catch (e: Throwable) {
                // 记录失败时不做任何事
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrash(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Throwable) {
            // ignore
        }
    }
}
