package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentDuplex

/**
 * An answer to the agent. Only an [opened] browser stream turns the
 * connection into a stream: it carries it as [upgrade], and whoever holds the
 * answer closes it, unless it hands it on.
 */
class CliAgentAnswer private constructor(
    val response: CliAgentMessages.Response.Kind,
    /** The stream that the connection becomes once [response] is written. */
    val upgrade: AgentDuplex?,
) {
    /** An answer after which the connection carries no stream. */
    constructor(response: CliAgentMessages.Response.Kind) : this(response, upgrade = null) {
        require(response !is CliAgentMessages.Response.Kind.BrowserOpen) {
            "An opened stream comes with its upgrade"
        }
    }

    companion object {
        /** Opens the stream of a browser extension: the connection becomes [upgrade]. */
        fun opened(upgrade: AgentDuplex) = CliAgentAnswer(
            response = CliAgentMessages.Response.Kind.BrowserOpen(CliAgentMessages.Empty),
            upgrade = upgrade,
        )
    }
}
