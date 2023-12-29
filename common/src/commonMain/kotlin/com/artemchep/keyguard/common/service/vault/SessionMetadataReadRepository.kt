package com.artemchep.keyguard.common.service.vault

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * @author Artem Chepurnyi
 */
interface SessionMetadataReadRepository {
    fun getLastPasswordUseTimestamp(): Flow<Instant?>
}
