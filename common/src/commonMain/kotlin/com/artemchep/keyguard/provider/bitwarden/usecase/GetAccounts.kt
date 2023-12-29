package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetAccountsImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetAccounts {
    companion object {
        private const val TAG = "GetAccounts.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
    )

    override fun invoke(): Flow<List<DAccount>> = tokenRepository
        .get()
        .map { list ->
            list
                .map(BitwardenToken::toDomain)
                .sorted()
        }
        .flowOn(dispatcher)
}
