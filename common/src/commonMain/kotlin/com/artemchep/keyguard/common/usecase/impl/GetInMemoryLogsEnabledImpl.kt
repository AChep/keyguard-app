package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetInMemoryLogsEnabledImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : GetInMemoryLogsEnabled {
    constructor(directDI: DirectDI) : this(
        inMemoryLogRepository = directDI.instance(),
    )

    override val value: Boolean
        get() = inMemoryLogRepository.isEnabled

    override fun invoke(): Flow<Boolean> = inMemoryLogRepository
        .getEnabled()
}
