package com.artemchep.keyguard.common.service.vault

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * @author Artem Chepurnyi
 */
interface SessionMetadataReadRepository {
    fun getLastPasswordUseTimestamp(): Flow<Instant?>
}
