package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomFillCipherHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface FillCipherHistoryDao {
    @Query("SELECT * FROM RoomFillCipherHistory")
    fun getAll(): Flow<List<RoomFillCipherHistory>>
}
