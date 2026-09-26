package com.artemchep.keyguard.common.service.filter.impl

import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GetCipherFiltersImpl(
    private val cipherFilterRepository: CipherFilterRepository,
) : GetCipherFilters {
    override fun invoke(): Flow<List<DCipherFilter>> = cipherFilterRepository.get()
}
