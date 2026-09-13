package com.wjf.fadebreak.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads why the always-resident service process last ended (system kill, crash, ...)
 * so the UI can tell the user when FadeBreak was stopped in the background.
 *
 * The `:ui` process is deliberately ignored: the system trims it freely and its death
 * says nothing about whether FadeBreak is working.
 */
object ProcessExit {

    private const val PREFS = "fadebreak_diagnostics"
    private const val KEY_LAST_SHOWN_TS = "last_shown_exit_ts"

    data class Report(val reason: String, val time: String)

    /** The newest not-yet-shown abnormal exit of the service process, or null. */
    fun consumeUnreported(context: Context): Report? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        }.getOrDefault(emptyList())

        val newest = exits
            .filter { it.processName == context.packageName }
            .maxByOrNull { it.timestamp }
            ?: return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // First observation only establishes a baseline; don't surface ancient history.
        if (!prefs.contains(KEY_LAST_SHOWN_TS)) {
            prefs.edit().putLong(KEY_LAST_SHOWN_TS, newest.timestamp).apply()
            return null
        }
        if (newest.timestamp <= prefs.getLong(KEY_LAST_SHOWN_TS, 0L)) return null
        prefs.edit().putLong(KEY_LAST_SHOWN_TS, newest.timestamp).apply()
        if (!isAbnormal(newest.reason)) return null

        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(newest.timestamp))
        return Report(reasonText(newest.reason), time)
    }

    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_OTHER -> true
        else -> false
    }

    private fun reasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
        ApplicationExitInfo.REASON_ANR -> "应用无响应"
        ApplicationExitInfo.REASON_CRASH -> "应用崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "底层崩溃"
        ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        ApplicationExitInfo.REASON_FREEZER -> "被系统冻结器结束"
        ApplicationExitInfo.REASON_OTHER -> "被系统回收"
        else -> "未知原因"
    }
}
