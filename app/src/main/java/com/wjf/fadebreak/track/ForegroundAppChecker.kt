package com.wjf.fadebreak.track

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Reads the current foreground app via UsageStats (requires "Usage access" permission). */
class ForegroundAppChecker(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private var lastQueryEnd = 0L
    private var lastPackage: String? = null

    // API 36 deprecates `unsafeCheckOpNoThrow` in favor of `checkOpNoThrow`, but that
    // overload is gated behind `FLAG_CHECK_OP_OVERLOAD_API_ENABLED` and may not exist at
    // runtime. The unsafe variant never throws and still works, so keep it deliberately.
    @Suppress("DEPRECATION")
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Incrementally scans usage events and remembers the last resumed package, so the
     * result stays valid even when no resume event happened in the recent past.
     */
    fun foregroundPackage(): String? {
        val end = System.currentTimeMillis()
        // Overlap the previous window slightly so a resumed event exactly on the
        // boundary cannot be missed between two queries.
        val begin = if (lastQueryEnd == 0L) {
            end - INITIAL_WINDOW_MS
        } else {
            (lastQueryEnd - OVERLAP_MS).coerceAtLeast(0L)
        }
        val events = usageStatsManager.queryEvents(begin, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }
        lastQueryEnd = end
        return lastPackage
    }

    /** Drop the cached package, e.g. after the screen turns off so the next session re-scans. */
    fun reset() {
        lastQueryEnd = 0L
        lastPackage = null
    }

    private companion object {
        const val INITIAL_WINDOW_MS = 60 * 60 * 1000L
        const val OVERLAP_MS = 5_000L
    }
}
