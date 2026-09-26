package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetEmailRelaysImpl(
    private val generatorEmailRelayRepository: GeneratorEmailRelayRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetEmailRelays {
    companion object {
        private const val TAG = "GetAccounts.bitwarden"
    }

    override fun invoke(): Flow<List<DGeneratorEmailRelay>> = generatorEmailRelayRepository
        .get()
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
}
