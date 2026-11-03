package com.artemchep.keyguard.common.service.environment

import kotlinx.coroutines.flow.StateFlow

/** The MCP integration, which lets MCP clients manage the environments. */
interface EnvironmentMcp {
    val state: StateFlow<EnvironmentMcpState>

    suspend fun setEnabled(enabled: Boolean)
}

data class EnvironmentMcpState(
    val enabled: Boolean = false,
    /** How MCP clients start the CLI; empty until the platform runtime describes it. */
    val configuration: String = "",
)
