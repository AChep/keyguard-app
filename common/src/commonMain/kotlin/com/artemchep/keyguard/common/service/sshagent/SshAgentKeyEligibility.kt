package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.isEligibleForAgent

fun DSecret.isEligibleForSshAgent(): Boolean =
    type == DSecret.Type.SshKey && isEligibleForAgent()
