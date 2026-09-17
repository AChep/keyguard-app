package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetUrlOverridesImpl(
    private val urlOverrideRepository: UrlOverrideRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetUrlOverrides {
    override fun invoke(): Flow<List<DGlobalUrlOverride>> = urlOverrideRepository
        .get()
        .map { list ->
            list
                .sorted()
        }
        .flowOn(dispatcher)
}
