package com.tianmeng.security.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.tianmeng.security.receiver.ShieldRestartReceiver

object ShieldWatchdog {

    private const val TAG = "ShieldWatchdog"
    private const val REQUEST_CODE = 2002
    private const val INTERVAL_MS = 5 * 60 * 1000L

    fun arm(context: Context) {
        try {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ShieldRestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pending
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Arm watchdog failed", e)
        }
    }

    fun disarm(context: Context) {
        try {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ShieldRestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.cancel(pending)
        } catch (e: Throwable) {
            Log.e(TAG, "Disarm watchdog failed", e)
        }
    }
}
