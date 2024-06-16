package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetInMemoryLogsImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : GetInMemoryLogs {
    constructor(directDI: DirectDI) : this(
        inMemoryLogRepository = directDI.instance(),
    )

    override fun invoke() = inMemoryLogRepository
        .get()
}
