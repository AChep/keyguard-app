package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.cli.agent.CliAgentAnswer
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages

/** Opens the stream of each browser extension that the CLI agent relays to the app. */
fun interface BrowserExtensionStreams {
    /**
     * Answers the agent's [request] to open a stream. An accepted stream comes
     * with the answer, and the connection serves it once the answer is
     * written; a refused one gets a stable error code.
     */
    suspend fun open(request: CliAgentMessages.BrowserOpenRequest): CliAgentAnswer
}
