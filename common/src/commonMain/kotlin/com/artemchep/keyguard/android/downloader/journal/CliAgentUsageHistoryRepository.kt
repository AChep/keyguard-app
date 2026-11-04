package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface CliAgentUsageHistoryRepository {
    fun getRecent(
        client: CliAgentUsageHistoryClient,
        limit: Long = 100L,
    ): Flow<List<DCliAgentUsageHistory>>

    fun getByCipherId(
        cipherId: String,
        limit: Long = 100L,
    ): Flow<List<DCliAgentUsageHistory>>

    fun getCount(
        client: CliAgentUsageHistoryClient,
    ): Flow<Long>

    fun put(model: DCliAgentUsageHistory): IO<Unit>

    fun removeByClient(
        client: CliAgentUsageHistoryClient,
    ): IO<Unit>

    /** Removes the entries of every client created before the [cutoff]. */
    fun removeOlderThan(
        cutoff: Instant,
    ): IO<Unit>
}
