package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs

class GetInMemoryLogsImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : GetInMemoryLogs {
    override fun invoke() = inMemoryLogRepository
        .get()
}
