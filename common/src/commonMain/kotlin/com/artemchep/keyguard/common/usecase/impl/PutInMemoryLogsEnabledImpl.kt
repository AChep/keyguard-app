package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutInMemoryLogsEnabledImpl(
    private val inMemoryLogRepository: InMemoryLogRepository,
) : PutInMemoryLogsEnabled {
    constructor(directDI: DirectDI) : this(
        inMemoryLogRepository = directDI.instance(),
    )

    override fun invoke(inMemoryLogsEnabled: Boolean): IO<Unit> = inMemoryLogRepository
        .setEnabled(inMemoryLogsEnabled)
}
