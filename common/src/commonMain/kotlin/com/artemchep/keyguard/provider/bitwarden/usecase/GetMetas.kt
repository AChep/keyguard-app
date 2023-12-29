package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenMetaRepository
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
class GetMetasImpl(
    private val metaRepository: BitwardenMetaRepository,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetMetas {
    companion object {
        private const val TAG = "GetMetas.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        metaRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = metaRepository
        .get()
        .map { list ->
            list
                .map(BitwardenMeta::toDomain)
        }
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DMeta>> = sharedFlow
}
