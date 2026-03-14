package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
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
class GetUrlOverridesImpl(
    private val urlOverrideRepository: UrlOverrideRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetUrlOverrides {
    constructor(directDI: DirectDI) : this(
        urlOverrideRepository = directDI.instance(),
    )

    override fun invoke(): Flow<List<DGlobalUrlOverride>> = urlOverrideRepository
        .get()
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
}
