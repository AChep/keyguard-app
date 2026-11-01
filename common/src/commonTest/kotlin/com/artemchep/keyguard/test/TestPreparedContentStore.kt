package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.dragout.PreparedContentLimits
import com.artemchep.keyguard.common.service.dragout.PreparedContentStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * A [PreparedContentStore] on the test's virtual clock: offers expire with
 * [TestScope.testScheduler], and released storage closes on the next `runCurrent()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.testPreparedContentStore(
    limits: PreparedContentLimits = PreparedContentLimits(),
    log: LogRepository = LogRepositoryBridge(emptyList()),
    scope: CoroutineScope = backgroundScope,
) = PreparedContentStore(
    scope = scope,
    logRepository = log,
    cleanupDispatcher = StandardTestDispatcher(testScheduler),
    limits = limits,
    timeSource = testScheduler.timeSource,
)
