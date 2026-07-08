package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow

interface GpgUsageHistoryRepository : BaseRepository<DGpgUsageHistory> {
    fun getRecent(
        limit: Long = 100L,
    ): Flow<List<DGpgUsageHistory>>

    fun getByCipherId(
        cipherId: String,
        limit: Long = 100L,
    ): Flow<List<DGpgUsageHistory>>

    fun getCount(): Flow<Long>

    fun removeAll(): IO<Unit>
}
