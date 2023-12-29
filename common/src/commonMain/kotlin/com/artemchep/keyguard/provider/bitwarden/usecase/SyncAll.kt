package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByToken
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SyncAllImpl(
    private val logRepository: LogRepository,
    private val tokenRepository: BitwardenTokenRepository,
    private val syncByToken: SyncByToken,
) : SyncAll {
    companion object {
        private const val TAG = "SyncAll.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        tokenRepository = directDI.instance(),
        syncByToken = directDI.instance(),
    )

    override fun invoke() = tokenRepository.getSnapshot()
        .flatMap { tokens ->
            logRepository.post(
                tag = TAG,
                message = "Loaded ${tokens.size} accounts from the store.",
            )

            // Sync all of the accounts simultaneously, on errors do not
            // interrupt other io-es.
            tokens
                .map { token ->
                    syncByToken(token)
                        .attempt()
                        .map { either ->
                            val accountId = AccountId(token.id)
                            accountId to either
                        }
                }
                .parallel(Dispatchers.IO)
                .map { list ->
                    list.toMap()
                }
        }
}
