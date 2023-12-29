package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM RoomBitwardenFolder")
    fun getAll(): Flow<List<RoomBitwardenFolder>>
}
