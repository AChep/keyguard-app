package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomOpenCipherHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface OpenCipherHistoryDao {
    @Query("SELECT * FROM RoomOpenCipherHistory ORDER BY timestamp DESC")
    fun getAll(): Flow<List<RoomOpenCipherHistory>>
}
