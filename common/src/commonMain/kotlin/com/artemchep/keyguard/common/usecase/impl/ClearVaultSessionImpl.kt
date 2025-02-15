package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ClearVaultSessionImpl(
    private val context: LeContext,
    private val putVaultSession: PutVaultSession,
) : ClearVaultSession {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
        putVaultSession = directDI.instance(),
    )

    override fun invoke(reason: TextHolder?): IO<Unit> = ioEffect {
        val clearVaultReasonText = reason?.let { textResource(it, context) }
        putVaultSession(MasterSession.Empty(reason = clearVaultReasonText))
    }.flatten()
}
