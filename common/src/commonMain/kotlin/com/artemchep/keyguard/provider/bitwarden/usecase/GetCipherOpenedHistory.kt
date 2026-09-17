package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.model.CipherOpenedHistoryMode
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import kotlinx.coroutines.flow.Flow

class GetCipherOpenedHistoryImpl(
    private val cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
) : GetCipherOpenedHistory {
    override fun invoke(
        mode: CipherOpenedHistoryMode,
    ): Flow<List<DCipherOpenedHistory>> = when (mode) {
        is CipherOpenedHistoryMode.Recent -> cipherHistoryOpenedRepository.getRecent()
        is CipherOpenedHistoryMode.Popular -> cipherHistoryOpenedRepository.getPopular()
    }
}
