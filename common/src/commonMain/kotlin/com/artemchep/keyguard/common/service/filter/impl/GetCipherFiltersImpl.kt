package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCipherFiltersImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : GetCipherFilters {
    constructor(
        directDI: DirectDI,
    ) : this(
        cipherFilterRepository = directDI.instance(),
    )

    override fun invoke(): Flow<List<DCipherFilter>> = cipherFilterRepository.get()
}
