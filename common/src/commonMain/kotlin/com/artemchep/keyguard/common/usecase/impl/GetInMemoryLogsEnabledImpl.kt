package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import kotlinx.coroutines.flow.Flow

class GetInMemoryLogsEnabledImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : GetInMemoryLogsEnabled {
    override val value: Boolean
        get() = inMemoryLogRepository.isEnabled

    override fun invoke(): Flow<Boolean> = inMemoryLogRepository
        .getEnabled()
}
