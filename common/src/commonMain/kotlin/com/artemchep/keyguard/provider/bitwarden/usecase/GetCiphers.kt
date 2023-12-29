package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetCiphersImpl(
    private val logRepository: LogRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val getPasswordStrength: GetPasswordStrength,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetCiphers {
    companion object {
        private const val TAG = "GetCiphers.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cipherRepository = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = cipherRepository
        .get()
        .map { list ->
            list
                .distinctBy { it.cipherId to it.accountId }
                .map {
                    it.toDomain(getPasswordStrength)
                }
        }
        .withLogTimeOfFirstEvent(logRepository, TAG)
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DSecret>> = sharedFlow
}
