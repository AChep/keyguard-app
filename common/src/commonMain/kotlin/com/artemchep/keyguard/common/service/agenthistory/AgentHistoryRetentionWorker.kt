package com.artemchep.keyguard.common.service.agenthistory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Deletes the agent request log entries that are older
 * than the retention period the user picked.
 */
interface AgentHistoryRetentionWorker {
    fun launch(scope: CoroutineScope): Job
}
