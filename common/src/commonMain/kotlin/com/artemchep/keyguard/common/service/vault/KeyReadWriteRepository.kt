package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.PersistedSession

/**
 * @author Artem Chepurnyi
 */
interface KeyReadWriteRepository : KeyReadRepository {
    fun put(session: PersistedSession?): IO<Unit>
}
