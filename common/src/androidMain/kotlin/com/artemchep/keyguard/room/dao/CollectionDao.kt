package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenCollection
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM RoomBitwardenCollection")
    fun getAll(): Flow<List<RoomBitwardenCollection>>
}
