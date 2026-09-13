package com.wjf.fadebreak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "break_events")
data class BreakEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAt: Long,
    val dismissedAt: Long,
    val visibleMs: Long,
    val taken: Boolean,
    val dayKey: String
)
