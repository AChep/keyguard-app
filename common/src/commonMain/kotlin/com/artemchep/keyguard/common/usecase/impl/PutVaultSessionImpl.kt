package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultSession
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutVaultSessionImpl(
    private val sessionReadWriteRepository: SessionReadWriteRepository,
) : PutVaultSession {
    constructor(directDI: DirectDI) : this(
        sessionReadWriteRepository = directDI.instance(),
    )

    override fun invoke(session: MasterSession): IO<Unit> = ioEffect {
        sessionReadWriteRepository.put(session)
    }
}
