package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface CipherHistoryOpenedRepository : BaseRepository<DCipherOpenedHistory> {
    fun getCount(): Flow<Long>

    fun getPopular(): Flow<List<DCipherOpenedHistory>>

    fun getRecent(): Flow<List<DCipherOpenedHistory>>

    fun getCredentialLastUsed(
        cipherId: String,
        credentialId: String,
    ): Flow<Instant?>

    /**
     * The time each of the [cipherIds] was last used, counting the uses of
     * the [types] only. A cipher that has none is absent.
     */
    fun getLastUsed(
        cipherIds: Collection<String>,
        types: Collection<Long>,
    ): Flow<Map<String, Instant>>

    fun removeAll(): IO<Unit>

    fun removeOlderThan(cutoff: Instant): IO<Unit>
}
