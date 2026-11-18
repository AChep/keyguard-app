package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Instant

fun interface RemoveCipherUsageHistoryOlderThan {
    operator fun invoke(cutoff: Instant): IO<Unit>
}
