package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg info: DownloadInfoEntity)

    @Query("SELECT * FROM DownloadInfoEntity WHERE id = :id")
    suspend fun getById(id: String): DownloadInfoEntity?

    @Query("SELECT * FROM DownloadInfoEntity WHERE id = :id")
    fun getByIdFlow(id: String): Flow<List<DownloadInfoEntity>>

    @Query(
        """
            SELECT * FROM DownloadInfoEntity WHERE 
                localCipherId = :localCipherId AND 
                remoteCipherId = :remoteCipherId AND 
                attachmentId = :attachmentId
                """,
    )
    suspend fun getByTag(
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
    ): DownloadInfoEntity?

    @Query("SELECT * FROM DownloadInfoEntity")
    fun getAll(): Flow<List<DownloadInfoEntity>>

    @Query("DELETE FROM DownloadInfoEntity WHERE id = :id")
    suspend fun removeById(id: String)

    @Query(
        """
            DELETE FROM DownloadInfoEntity WHERE 
                localCipherId = :localCipherId AND 
                remoteCipherId = :remoteCipherId AND 
                attachmentId = :attachmentId
                """,
    )
    suspend fun removeByTag(
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
    )
}
