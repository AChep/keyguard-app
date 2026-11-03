package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardRuntime
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.cli.agent.supervise
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.cli.cliMcpArguments
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** App-only worker. AutoFill and iOS never start a local helper. */
internal class CliAgentController(private val ctx: CoreContext) {
    private var worker: Job? = null

    fun start() {
        if (worker != null || ctx.runtime != KeyguardRuntime.APP) return
        val runtime = createCliAgentRuntime()
        // Without a CLI and local .env files there is nothing to configure (iOS).
        if (runtime.command == null && !runtime.supportsMounts) return
        val host = ctx.koin.get<CliAgentHost>()
        host.mcp.configure(runtime.command ?: "keyguard", cliMcpArguments(runtime.publicSocket))
        host.mounts.configure(runtime.supportsMounts, runtime.requiresDirectoryGrant)
        host.cli.configureRuntime(path = runtime.command, capabilities = runtime.cliCapabilities)
        if (runtime.command != null) {
            worker = ctx.backgroundScope.launch {
                host.supervise(
                    available = runtime.isBinaryAvailable,
                    start = { scope ->
                        // The runtime keeps its own copy of the token.
                        ctx.koin.get<CryptoGenerator>().seed(AgentIpcAuthenticator.TOKEN_SIZE).useAndClear { token ->
                            runtime.start(scope, host, token)
                        }
                    },
                    join = { handle ->
                        handle.join()
                        null
                    },
                    stop = { handle -> handle?.stop() },
                )
            }
        }
    }
}
