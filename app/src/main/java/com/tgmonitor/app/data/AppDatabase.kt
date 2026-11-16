package com.tgmonitor.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class,
        MessageEntity::class,
        MessageVersionEntity::class,
        ChangeEventEntity::class,
        ScanHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun messageDao(): MessageDao
    abstract fun versionDao(): VersionDao
    abstract fun changeDao(): ChangeDao
    abstract fun scanDao(): ScanDao

    companion object {
        // No destructive fallback on purpose: history must never be dropped silently.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tgmonitor.db").build()
    }
}
