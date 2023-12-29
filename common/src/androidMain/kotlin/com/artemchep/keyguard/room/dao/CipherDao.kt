package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenCipher
import kotlinx.coroutines.flow.Flow

@Dao
interface CipherDao {
    @Query("SELECT * FROM RoomBitwardenCipher")
    fun getAll(): Flow<List<RoomBitwardenCipher>>
}
