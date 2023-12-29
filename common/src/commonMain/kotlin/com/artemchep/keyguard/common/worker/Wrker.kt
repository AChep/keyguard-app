package com.artemchep.keyguard.common.worker

import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

interface Wrker {
    fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    )
}
