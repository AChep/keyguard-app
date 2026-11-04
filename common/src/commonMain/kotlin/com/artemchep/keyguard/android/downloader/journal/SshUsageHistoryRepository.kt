package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface SshUsageHistoryRepository : BaseRepository<DSshUsageHistory> {
    fun getRecent(
        limit: Long = 100L,
    ): Flow<List<DSshUsageHistory>>

    fun getByCipherId(
        cipherId: String,
        limit: Long = 100L,
    ): Flow<List<DSshUsageHistory>>

    fun getCount(): Flow<Long>

    fun removeAll(): IO<Unit>

    /** Removes the entries created before the [cutoff]. */
    fun removeOlderThan(
        cutoff: Instant,
    ): IO<Unit>
}
