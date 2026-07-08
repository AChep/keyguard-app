package com.artemchep.keyguard.common.service.gpgagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface GpgAgentPublicKeySyncer {
    fun launch(scope: CoroutineScope): Job
}
