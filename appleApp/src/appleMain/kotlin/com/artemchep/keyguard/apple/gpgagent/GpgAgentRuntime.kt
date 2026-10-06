package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import kotlinx.coroutines.CoroutineScope

class GpgAgentRuntimeConfig(
    val authToken: ByteArray,
    val processor: GpgAgentRequestProcessor,
    val onTerminated: (String?) -> Unit,
    val log: (String) -> Unit,
)

interface GpgAgentRuntimeHandle {
    val isRunning: Boolean
    val gpgHome: String?
    val agentSocket: String?
    fun stop()
}

interface GpgAgentRuntime {
    val isBinaryAvailable: Boolean
    val gpgHome: String?
    val agentSocket: String?

    /** Returns after the helper is ready; recoverable failures may throw. */
    suspend fun start(
        scope: CoroutineScope,
        config: GpgAgentRuntimeConfig,
    ): GpgAgentRuntimeHandle?
}

internal expect fun createGpgAgentRuntime(): GpgAgentRuntime
