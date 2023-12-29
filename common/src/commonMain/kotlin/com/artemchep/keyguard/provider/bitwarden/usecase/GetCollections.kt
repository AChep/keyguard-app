package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCollectionRepository
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
class GetCollectionsImpl(
    private val logRepository: LogRepository,
    private val collectionRepository: BitwardenCollectionRepository,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetCollections {
    companion object {
        private const val TAG = "GetCollections.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        collectionRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = collectionRepository
        .get()
        .map { list ->
            list
                .distinctBy { it.collectionId to it.accountId }
                .map(BitwardenCollection::toDomain)
        }
        .withLogTimeOfFirstEvent(logRepository, TAG)
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DCollection>> = sharedFlow
}
