package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PutVaultSessionImpl(
    private val sessionReadWriteRepository: SessionReadWriteRepository,
) : PutVaultSession {

    override fun invoke(session: MasterSession): IO<Unit> = ioEffect {
        var published = false
        try {
            withContext(Dispatchers.Main.immediate) {
                sessionReadWriteRepository.put(session)
                // Replacing the previous vault may cancel the caller. Ownership transfers
                // here, before withContext checks cancellation on its return dispatcher.
                published = true
            }
        } finally {
            if (!published) {
                (session as? MasterSession.Key)?.session?.close()
            }
        }
    }
}
