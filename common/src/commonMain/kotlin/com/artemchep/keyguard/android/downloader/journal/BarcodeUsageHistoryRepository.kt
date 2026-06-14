package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DBarcodeUsageHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow

interface BarcodeUsageHistoryRepository : BaseRepository<DBarcodeUsageHistory> {
    fun getById(
        id: String,
    ): Flow<DBarcodeUsageHistory?>

    fun getRecent(
        limit: Long = 100L,
    ): Flow<List<DBarcodeUsageHistory>>

    fun removeAll(): IO<Unit>
}
