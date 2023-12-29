package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenToken
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM RoomBitwardenToken")
    fun getAll(): Flow<List<RoomBitwardenToken>>
}
