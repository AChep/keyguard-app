package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.MasterSession

/**
 * @author Artem Chepurnyi
 */
interface SessionReadWriteRepository : SessionReadRepository {
    fun put(key: MasterSession)
}
