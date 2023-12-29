package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByToken
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SyncByIdImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val syncByToken: SyncByToken,
) : SyncById {
    companion object {
        private const val TAG = "SyncById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        syncByToken = directDI.instance(),
    )

    override fun invoke(accountId: AccountId): IO<Boolean> = tokenRepository.getSnapshot()
        // Find the correct account model
        // by its account id.
        .map { accounts ->
            accounts
                .firstOrNull { it.id == accountId.id }
        }
        .flatMap { token ->
            if (token == null) {
                // We could not find the tokens
                // for this account.
                return@flatMap io(false)
            }

            syncByToken(token)
                .map {
                    // Handled successfully.
                    true
                }
        }
}
