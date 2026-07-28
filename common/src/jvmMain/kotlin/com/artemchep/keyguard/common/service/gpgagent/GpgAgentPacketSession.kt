package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentPacketChannel
import com.artemchep.keyguard.common.service.agent.runAgentPacketSession

internal suspend fun runGpgAgentPacketSession(
    channel: AgentPacketChannel,
    rpcHandler: GpgAgentRpcHandler,
    initialContext: GpgAgentRpcRequestContext,
    codec: GpgAgentProtoCodec = GpgAgentProtoCodec,
) {
    runAgentPacketSession(
        channel = channel,
        initialAuthenticated = initialContext.authenticated,
        allowAuthenticate = initialContext.allowAuthenticate,
        decodeRequest = codec::decodeRequest,
        encodeResponse = codec::encodeResponse,
        isAuthenticateRequest = { it.authenticate != null },
        isAuthenticateSuccess = { it.authenticate?.success == true },
        handleRequest = { request, authenticated ->
            rpcHandler.processRequest(
                request = request,
                context = initialContext.copy(authenticated = authenticated),
            )
        },
    )
}
