package com.wjf.fadebreak.data

import androidx.room.Dao
import androidx.room.Insert

@Dao
interface BreakDao {

    @Insert
    suspend fun insert(event: BreakEvent): Long
}
