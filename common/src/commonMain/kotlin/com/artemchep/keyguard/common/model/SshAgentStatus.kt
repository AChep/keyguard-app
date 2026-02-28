package com.artemchep.keyguard.common.model

sealed interface SshAgentStatus {
    data object Unsupported : SshAgentStatus

    data object Starting : SshAgentStatus

    data object Ready : SshAgentStatus

    data object Failed : SshAgentStatus

    data object Stopped : SshAgentStatus
}
