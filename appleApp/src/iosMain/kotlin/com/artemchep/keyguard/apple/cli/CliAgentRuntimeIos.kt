package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import kotlinx.coroutines.CoroutineScope

internal actual fun createCliAgentRuntime(): CliAgentRuntime = NoCliAgentRuntime

private object NoCliAgentRuntime : CliAgentRuntime {
    override val isBinaryAvailable = false
    override val command: String? = null
    override val publicSocket: String? = null
    override val supportsMounts = false
    override val requiresDirectoryGrant = false
    override val cliCapabilities = emptySet<CliPlatformCapability>()
    override suspend fun start(
        scope: CoroutineScope,
        host: CliAgentHost,
        authToken: ByteArray,
    ): CliAgentRuntimeHandle =
        error("Local developer integration is unavailable on iOS")
}
