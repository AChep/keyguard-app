package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface CipherHistoryOpenedRepository : BaseRepository<DCipherOpenedHistory> {
    fun getCount(): Flow<Long>

    fun getPopular(): Flow<List<DCipherOpenedHistory>>

    fun getRecent(): Flow<List<DCipherOpenedHistory>>

    fun getCredentialLastUsed(
        cipherId: String,
        credentialId: String,
    ): Flow<Instant?>

    fun removeAll(): IO<Unit>
}
