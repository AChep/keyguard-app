package com.artemchep.keyguard.common.service.sshagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface SshAgentPublicKeySyncer {
    fun launch(scope: CoroutineScope): Job
}
