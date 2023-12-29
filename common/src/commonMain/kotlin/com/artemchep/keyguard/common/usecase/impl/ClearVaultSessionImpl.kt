package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ClearVaultSessionImpl(
    private val putVaultSession: PutVaultSession,
) : ClearVaultSession {
    constructor(directDI: DirectDI) : this(
        putVaultSession = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = putVaultSession(MasterSession.Empty())
}
