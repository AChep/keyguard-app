package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
class GetUrlBlocksImpl(
    private val urlBlockRepository: UrlBlockRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetUrlBlocks {
    private val sharedFlow = urlBlockRepository
        .get()
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
        .shareIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(5000L),
            replay = 1,
        )

    constructor(directDI: DirectDI) : this(
        urlBlockRepository = directDI.instance(),
    )

    override fun invoke(): Flow<List<DGlobalUrlBlock>> = sharedFlow
}
