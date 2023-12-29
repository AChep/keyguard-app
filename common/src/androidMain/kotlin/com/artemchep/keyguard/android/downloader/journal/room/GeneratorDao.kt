package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val HISTORY_FETCH_LIMIT = 10_000

@Dao
interface GeneratorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg info: GeneratorEntity)

    @Query("SELECT * FROM GeneratorEntity ORDER BY createdDate DESC LIMIT $HISTORY_FETCH_LIMIT")
    fun getAll(): Flow<List<GeneratorEntity>>

    @Query("DELETE FROM GeneratorEntity")
    suspend fun removeAll()

    @Query("DELETE FROM GeneratorEntity WHERE id IN (:ids)")
    suspend fun removeByIds(ids: Set<String>)
}
