package com.artemchep.keyguard.common.service.agent

/** The state of the SSH or GPG agent connection a request arrives on. */
data class AgentRpcRequestContext(
    val authenticated: Boolean,
    /** False where the transport authenticated the helper, as on Android. */
    val allowAuthenticate: Boolean,
)

/**
 * The checks every SSH and GPG agent request passes before its dispatch: one
 * variant at most, no authentication where the transport did it, and
 * authentication before anything else. Null lets the request through.
 */
internal fun agentRpcRequestError(
    variantCount: Int,
    isAuthenticate: Boolean,
    context: AgentRpcRequestContext,
): AgentIpcMessages.ErrorResponse? = when {
    variantCount > 1 -> AgentIpcMessages.ErrorResponse(
        message = "Malformed request: multiple request variants set",
        code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
    )

    !context.allowAuthenticate && isAuthenticate -> AgentIpcMessages.ErrorResponse(
        message = "AuthenticateRequest is not supported on this transport",
        code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
    )

    !context.authenticated && !isAuthenticate -> AgentIpcMessages.ErrorResponse(
        message = "Not authenticated. Send AuthenticateRequest first.",
        code = AgentIpcMessages.ErrorCode.NOT_AUTHENTICATED,
    )

    else -> null
}
