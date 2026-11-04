package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Instant

/**
 * Removes the SSH agent, GPG agent and CLI agent request log
 * entries created before the given instant.
 */
interface RemoveAgentHistoryOlderThan : (Instant) -> IO<Unit>
