package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.usecase.GetCipherOpenedCount
import kotlinx.coroutines.flow.Flow

class GetCipherOpenedCountImpl(
    private val cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
) : GetCipherOpenedCount {
    override fun invoke(
    ): Flow<Long> = cipherHistoryOpenedRepository.getCount()
}
