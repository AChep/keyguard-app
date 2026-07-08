package com.artemchep.keyguard.common.model

sealed interface AgentStatus {
    data object Unsupported : AgentStatus

    data object Starting : AgentStatus

    data object Ready : AgentStatus

    data object Failed : AgentStatus

    data object Stopped : AgentStatus
}
