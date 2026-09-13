package com.wjf.fadebreak.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BreakRepository(private val dao: BreakDao) {

    suspend fun record(triggeredAt: Long, dismissedAt: Long, visibleMs: Long, taken: Boolean) {
        dao.insert(
            BreakEvent(
                triggeredAt = triggeredAt,
                dismissedAt = dismissedAt,
                visibleMs = visibleMs,
                taken = taken,
                dayKey = dayKey(dismissedAt)
            )
        )
    }

    private fun dayKey(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(timeMs))
}
