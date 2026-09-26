package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled

class PutInMemoryLogsEnabledImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : PutInMemoryLogsEnabled {
    override fun invoke(inMemoryLogsEnabled: Boolean): IO<Unit> = inMemoryLogRepository
        .setEnabled(inMemoryLogsEnabled)
}
