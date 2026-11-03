package com.artemchep.keyguard.common.service.cli.agent

/** A failed answer to the agent, with a stable, non-sensitive error [code]. */
internal fun cliAgentError(code: String): CliAgentMessages.Response.Kind =
    CliAgentMessages.Response.Kind.Error(CliAgentMessages.ErrorResponse(code))
