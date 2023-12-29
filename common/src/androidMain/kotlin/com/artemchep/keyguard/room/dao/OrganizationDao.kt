package com.artemchep.keyguard.room.dao

import androidx.room.Dao
import androidx.room.Query
import com.artemchep.keyguard.room.RoomBitwardenOrganization
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizationDao {
    @Query("SELECT * FROM RoomBitwardenOrganization")
    fun getAll(): Flow<List<RoomBitwardenOrganization>>
}
