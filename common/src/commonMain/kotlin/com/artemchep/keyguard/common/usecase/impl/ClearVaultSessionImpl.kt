package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlin.time.Clock

class ClearVaultSessionImpl(
    private val context: LeContext,
    private val putVaultSession: PutVaultSession,
) : ClearVaultSession {
    override fun invoke(
        type: LockReason,
        reason: TextHolder,
    ): IO<Unit> = ioEffect {
        val now = Clock.System.now()
        val info = kotlin.run {
            val reasonTranslated = textResource(reason, context)
            MasterSession.Empty.LockInfo(
                timestamp = now,
                type = type,
                reason = reasonTranslated,
            )
        }

        val session = MasterSession.Empty(lockInfo = info)
        putVaultSession(session)
    }.flatten()
}
