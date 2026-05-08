package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret

internal fun DSecret.isEligibleForSshAgent(): Boolean =
    type == DSecret.Type.SshKey && !deleted
