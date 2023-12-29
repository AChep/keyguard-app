package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DownloadInfoEntity::class,
        GeneratorEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DownloadDatabaseTypeConverter::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadInfoDao(): DownloadInfoDao
    abstract fun generatorDao(): GeneratorDao
}
