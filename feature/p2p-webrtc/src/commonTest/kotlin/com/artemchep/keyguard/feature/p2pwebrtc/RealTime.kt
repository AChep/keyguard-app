package com.artemchep.keyguard.feature.p2pwebrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** `runTest` skips delays; native calls need the real clock. */
internal fun realTime(block: suspend CoroutineScope.() -> Unit) =
    runTest(timeout = 90.seconds) { withContext(Dispatchers.Default) { block() } }
