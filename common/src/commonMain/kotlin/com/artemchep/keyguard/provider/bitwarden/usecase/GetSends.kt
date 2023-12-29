package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenSendRepository
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
class GetSendsImpl(
    private val logRepository: LogRepository,
    private val sendRepository: BitwardenSendRepository,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetSends {
    companion object {
        private const val TAG = "GetSends.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        sendRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = sendRepository
        .get()
        .map { list ->
            list
                .map {
                    it.toDomain()
                }
        }
        .withLogTimeOfFirstEvent(logRepository, TAG)
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DSend>> = sharedFlow
}
