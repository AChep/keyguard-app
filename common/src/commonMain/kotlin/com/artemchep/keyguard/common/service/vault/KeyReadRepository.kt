package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.PersistedSession
import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface KeyReadRepository {
    /**
     * Returns a flow of master key.
     */
    fun get(): Flow<PersistedSession?>
}
