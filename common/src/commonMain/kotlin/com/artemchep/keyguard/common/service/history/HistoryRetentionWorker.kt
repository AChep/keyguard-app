package com.artemchep.keyguard.common.service.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Applies each local history's retention period while the vault is unlocked. */
interface HistoryRetentionWorker {
    fun launch(scope: CoroutineScope): Job
}
