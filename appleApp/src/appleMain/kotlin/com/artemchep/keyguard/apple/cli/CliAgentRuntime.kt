package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import kotlinx.coroutines.CoroutineScope

internal interface CliAgentRuntime {
    /** Whether this build bundles the agent; without it, nothing can start. */
    val isBinaryAvailable: Boolean

    /** The bundled `keyguard` CLI, `null` without one. */
    val command: String?
    val publicSocket: String?
    val supportsMounts: Boolean

    /** Whether the agent is sandboxed, so it opens each directory with a bookmark that the app hands over. */
    val requiresDirectoryGrant: Boolean

    /** What the platform lets the bundled CLI do. */
    val cliCapabilities: Set<CliPlatformCapability>
    suspend fun start(
        scope: CoroutineScope,
        host: CliAgentHost,
        authToken: ByteArray,
    ): CliAgentRuntimeHandle
}

internal interface CliAgentRuntimeHandle {
    /** Suspends until the helper has stopped. */
    suspend fun join()
    fun stop()
}

internal expect fun createCliAgentRuntime(): CliAgentRuntime
