package com.artemchep.keyguard.apple.gpgagent

import kotlinx.coroutines.CoroutineScope

internal actual fun createGpgAgentRuntime(): GpgAgentRuntime = NoopGpgAgentRuntime

private object NoopGpgAgentRuntime : GpgAgentRuntime {
    override val isBinaryAvailable = false
    override val gpgHome: String? = null
    override val agentSocket: String? = null

    override suspend fun start(
        scope: CoroutineScope,
        config: GpgAgentRuntimeConfig,
    ): GpgAgentRuntimeHandle? = null
}
