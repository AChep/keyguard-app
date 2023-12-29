package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlinx.datetime.Instant

@ProvidedTypeConverter
class DownloadDatabaseTypeConverter {
    @TypeConverter
    fun instantToLong(instant: Instant) = instant.toEpochMilliseconds()

    @TypeConverter
    fun longToInstant(epochMilliseconds: Long) = Instant.fromEpochMilliseconds(epochMilliseconds)
}
