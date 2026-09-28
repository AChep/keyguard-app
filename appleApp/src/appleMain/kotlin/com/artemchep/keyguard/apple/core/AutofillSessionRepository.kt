package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import kotlinx.coroutines.flow.flowOf

/** An extension must neither restore nor overwrite the containing app's saved key. */
internal class AutofillSessionRepository : KeyReadWriteRepository {
    override fun get() = flowOf<PersistedSession?>(null)

    override fun put(session: PersistedSession?): IO<Unit> = ioUnit()
}
