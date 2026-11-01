package com.tianmeng.security.manager

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tianmeng.security.data.Prefs

/**
 * 一键静音：把所有音频流压到 0，并持续循环压制，
 * 防止锁机病毒反复把音量调回去继续播放警报声。
 */
object MuteManager {

    private const val TAG = "MuteManager"
    private const val REAPPLY_INTERVAL = 300L

    private val handler = Handler(Looper.getMainLooper())

    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM
    )

    @Volatile
    private var enabled = false
    private var appContext: Context? = null
    private var appliedDnd = false

    private val reapplyTask = object : Runnable {
        override fun run() {
            if (!enabled) return
            applyMute()
            handler.postDelayed(this, REAPPLY_INTERVAL)
        }
    }

    fun isEnabled(): Boolean = enabled || Prefs.muteGuard

    fun enable(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!enabled) {
            if (Prefs.muteSavedVolumes == null) saveState(ctx)
            enabled = true
            Prefs.muteGuard = true
        }
        applyMute()
        handler.removeCallbacks(reapplyTask)
        handler.postDelayed(reapplyTask, REAPPLY_INTERVAL)
    }

    fun disable(context: Context) {
        enabled = false
        handler.removeCallbacks(reapplyTask)
        Prefs.muteGuard = false
        try {
            restoreState(context.applicationContext)
        } catch (e: Throwable) {
            Log.e(TAG, "restore sound failed", e)
        }
        appContext = null
    }

    /**
     * 进程被杀或服务未正常销毁时，缓存里可能还留着静音前的音量。
     * 下次打开应用若静音已关闭，直接把声音恢复，避免一直没声音。
     */
    fun recoverIfStale(context: Context) {
        if (Prefs.muteGuard || enabled) return
        if (Prefs.muteSavedVolumes == null &&
            Prefs.muteSavedRinger == -1 &&
            Prefs.muteSavedDnd == -1
        ) return
        try {
            restoreState(context.applicationContext)
        } catch (e: Throwable) {
            Log.e(TAG, "recover stale mute state failed", e)
        }
    }

    /** 紧急情况下临时压一次（不改变静音守卫开关） */
    fun muteOnce(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        streams.forEach { stream ->
            try {
                am.setStreamVolume(stream, 0, 0)
            } catch (e: Throwable) {
                Log.w(TAG, "mute stream $stream failed", e)
            }
        }
    }

    private fun applyMute() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            streams.forEach { stream ->
                try {
                    am.setStreamVolume(stream, 0, 0)
                } catch (e: Throwable) {
                    Log.w(TAG, "mute stream $stream failed", e)
                }
            }
            try {
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (e: Throwable) {
                Log.w(TAG, "set ringer silent failed", e)
            }
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            try {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                appliedDnd = true
            } catch (e: Throwable) {
                Log.w(TAG, "set DND none failed", e)
            }
        }
    }

    private fun saveState(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val volumes = streams.map { stream ->
            try {
                am.getStreamVolume(stream)
            } catch (e: Throwable) {
                0
            }
        }
        // 如果开启前本来就已经全部为 0，不要把这组全零当作还原目标，
        // 否则关闭静音后依旧没有声音。
        val savedVolumes = if (volumes.all { it == 0 }) defaultVolumes(am) else volumes
        Prefs.muteSavedVolumes = savedVolumes.joinToString(",")
        Prefs.muteSavedRinger = try {
            am.ringerMode
        } catch (e: Throwable) {
            -1
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Prefs.muteSavedDnd = if (nm != null && nm.isNotificationPolicyAccessGranted) {
            try {
                nm.currentInterruptionFilter
            } catch (e: Throwable) {
                -1
            }
        } else {
            -1
        }
    }

    private fun defaultVolumes(am: AudioManager?): List<Int> {
        val ratios = listOf(0.5f, 0.5f, 0.5f, 0.7f, 0.3f)
        return streams.mapIndexed { index, stream ->
            val max = try {
                am?.getStreamMaxVolume(stream) ?: 0
            } catch (e: Throwable) {
                0
            }
            if (max <= 0) 0 else (max * ratios[index]).toInt().coerceAtLeast(1)
        }
    }

    private fun restoreState(ctx: Context) {
        val savedDnd = Prefs.muteSavedDnd
        val savedRinger = Prefs.muteSavedRinger
        val savedVolumes = Prefs.muteSavedVolumes
        if (savedVolumes == null && savedRinger == -1 && savedDnd == -1) return

        // 1. 先退出完全勿扰：勿扰不关，系统会强制保持静音，响铃模式改了也会被覆盖
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            val target = when {
                savedDnd >= NotificationManager.INTERRUPTION_FILTER_ALL -> savedDnd
                appliedDnd -> NotificationManager.INTERRUPTION_FILTER_ALL
                else -> -1
            }
            if (target >= NotificationManager.INTERRUPTION_FILTER_ALL) {
                try {
                    nm.setInterruptionFilter(target)
                } catch (e: Throwable) {
                    Log.w(TAG, "restore DND failed", e)
                }
            }
        }

        // 2. 恢复各音频流音量
        val am = ctx.getSystemService(AudioManager::class.java)
        val saved = savedVolumes
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        val allZero = saved.size == streams.size && saved.all { it == 0 }
        val restoredVolumes = when {
            allZero -> defaultVolumes(am)
            saved.size == streams.size -> saved
            else -> null
        }
        if (am != null && restoredVolumes != null) {
            streams.forEachIndexed { index, stream ->
                try {
                    am.setStreamVolume(stream, restoredVolumes[index], 0)
                } catch (e: Throwable) {
                    Log.w(TAG, "restore stream $stream failed", e)
                }
            }
        }

        // 3. 最后恢复响铃模式；记录无效或原本全零时回落正常模式，保证一定能出声
        val ringer = if (!allZero &&
            savedRinger in AudioManager.RINGER_MODE_SILENT..AudioManager.RINGER_MODE_NORMAL
        ) {
            savedRinger
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }
        if (am != null) {
            try {
                am.ringerMode = ringer
            } catch (e: Throwable) {
                Log.w(TAG, "restore ringer failed", e)
            }
            // 勿扰切换是异步的，稍后再设一次，避免被系统覆盖回静音
            handler.postDelayed({
                try {
                    am.ringerMode = ringer
                } catch (e: Throwable) {
                    Log.w(TAG, "restore ringer retry failed", e)
                }
            }, 500)
        }

        appliedDnd = false
        Prefs.muteSavedVolumes = null
        Prefs.muteSavedRinger = -1
        Prefs.muteSavedDnd = -1
    }
}
