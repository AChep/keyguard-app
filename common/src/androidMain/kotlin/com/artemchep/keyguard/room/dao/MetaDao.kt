package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface MetaDao {
    @Query("SELECT * FROM RoomBitwardenMeta")
    fun getAll(): Flow<List<RoomBitwardenMeta>>
}
