package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Instant

/**
 * @author Artem Chepurnyi
 */
interface SessionMetadataReadWriteRepository : SessionMetadataReadRepository {
    fun setLastPasswordUseTimestamp(
        instant: Instant?,
    ): IO<Unit>
}
