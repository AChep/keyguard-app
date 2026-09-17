package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetAccountsImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetAccounts {
    companion object {
        private const val TAG = "GetAccounts"
    }

    override fun invoke(): Flow<List<DAccount>> = tokenRepository
        .get()
        .map { list ->
            list
                .map(ServiceToken::toDomain)
                .sorted()
        }
        .flowOn(dispatcher)
}
