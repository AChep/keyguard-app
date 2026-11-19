package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.usecase.GetCipherLastUsed
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

class GetCipherLastUsedImpl(
    private val cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
) : GetCipherLastUsed {
    private companion object {
        // Stay below SQLite's traditional 999 bind-parameter limit.
        const val CIPHER_IDS_BATCH_SIZE = 900

        val TYPES = listOf(
            CipherHistoryType.USED_AUTOFILL,
            CipherHistoryType.USED_PASSKEY,
        )
    }

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Map<String, Instant>> = ioEffect {
        cipherIds
            .chunked(CIPHER_IDS_BATCH_SIZE)
            .fold(emptyMap<String, Instant>()) { acc, batch ->
                acc + cipherHistoryOpenedRepository
                    .getLastUsed(
                        cipherIds = batch,
                        types = TYPES,
                    )
                    .first()
            }
    }
}
