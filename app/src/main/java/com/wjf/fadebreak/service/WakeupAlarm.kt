package com.wjf.fadebreak.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.track.WakeupAlarmReceiver

/**
 * Reliable wake-up safety net for [WakeupScheduler]. `Handler.postDelayed` gets no CPU
 * while the device is in Doze, so mirror each scheduled wake with a `setAndAllowWhileIdle`
 * alarm, which is delivered even in low-power idle.
 *
 * Inexact on purpose: no `SCHEDULE_EXACT_ALARM` permission is required, and the Handler
 * still handles on-time wake-ups. This only rescues ones Doze/vendor freezing delayed.
 */
class WakeupAlarm(context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WakeupAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleAt(triggerElapsedRealtime: Long) {
        val result = runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (exact) {
                // Exact so Doze cannot batch/defer a short interval.
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtime,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerElapsedRealtime,
                    pendingIntent
                )
            }
        }
        result.exceptionOrNull()?.let { DebugLog.e("alarm schedule failed", it) }
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent)
    }

    private companion object {
        const val REQUEST_CODE = 0x57414B45 // "WAKE"
    }
}
