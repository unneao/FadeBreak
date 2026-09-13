package com.wjf.fadebreak.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BreakEvent::class], version = 1, exportSchema = false)
abstract class BreakDatabase : RoomDatabase() {

    abstract fun breakDao(): BreakDao

    companion object {
        @Volatile
        private var instance: BreakDatabase? = null

        fun get(context: Context): BreakDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        BreakDatabase::class.java,
                        "fadebreak.db"
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
