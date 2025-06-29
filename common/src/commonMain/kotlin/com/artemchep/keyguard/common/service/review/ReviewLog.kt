package com.artemchep.keyguard.common.service.review

import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface ReviewLog {
    fun setLastRequestedAt(
        requestedAt: Instant,
    ): IO<Unit>

    fun getLastRequestedAt(): Flow<Instant?>
}
