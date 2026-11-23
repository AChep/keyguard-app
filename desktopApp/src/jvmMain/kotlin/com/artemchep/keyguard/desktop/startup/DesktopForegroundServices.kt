package com.artemchep.keyguard.desktop.startup

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** Process foreground work, independent of whether the main window was ever created. */
internal fun Flow<LeLifecycleState>.withDesktopForegroundServices(
    keepAlive: suspend () -> Unit,
    refreshStartup: suspend () -> Unit,
): Flow<Unit> = merge(
    // Separate collectors: a blocking OS status call outlives cancellation and must not delay the lease.
    onState(minActiveState = LeLifecycleState.RESUMED) {
        keepAlive()
    },
    onState(minActiveState = LeLifecycleState.RESUMED) {
        // Registration reports its errors in its own state.
        runCatchingNonFatal { refreshStartup() }
    },
)
