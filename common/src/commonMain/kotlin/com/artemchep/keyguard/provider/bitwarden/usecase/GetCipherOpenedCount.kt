package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.usecase.GetCipherOpenedCount
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCipherOpenedCountImpl(
    private val cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
) : GetCipherOpenedCount {
    constructor(directDI: DirectDI) : this(
        cipherHistoryOpenedRepository = directDI.instance(),
    )

    override fun invoke(
    ): Flow<Long> = cipherHistoryOpenedRepository.getCount()
}
