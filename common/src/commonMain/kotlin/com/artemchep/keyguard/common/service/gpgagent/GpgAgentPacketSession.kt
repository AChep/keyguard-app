package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthentication
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcFraming
import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.agent.AgentRpcPacketProtocol

/** One connection's transport-independent GPG IPC authentication and dispatch. */
class GpgAgentPacketSession private constructor(
    protocol: AgentRpcPacketProtocol<GpgAgentMessages.IpcRequest, GpgAgentMessages.IpcResponse>,
) : AgentPacketSession<GpgAgentMessages.IpcRequest, GpgAgentMessages.IpcResponse>(
    protocol = protocol,
    authenticated = !protocol.allowAuthenticate,
) {
    /** A session that authenticates the agent with its launch [authToken]. */
    constructor(
        requestProcessor: GpgAgentRequestProcessor,
        authToken: ByteArray,
    ) : this(gpgAgentPacketProtocol(requestProcessor, AgentIpcAuthenticator(authToken)))

    /**
     * A session of [rpcHandler]. Where the transport has authenticated the
     * agent, the session starts authenticated and refuses another handshake.
     * A response over [maxPacketSize] is answered with an error.
     */
    internal constructor(
        rpcHandler: GpgAgentRpcHandler,
        authenticatedByTransport: Boolean,
        maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
        onOversizeResponse: (size: Int) -> Unit = {},
    ) : this(
        gpgAgentPacketProtocol(
            rpcHandler = rpcHandler,
            allowAuthenticate = !authenticatedByTransport,
            maxPacketSize = maxPacketSize,
            onOversizeResponse = onOversizeResponse,
        ),
    )
}

private fun gpgAgentPacketProtocol(
    requestProcessor: GpgAgentRequestProcessor,
    authenticator: AgentIpcAuthenticator,
) = gpgAgentPacketProtocol(
    rpcHandler = GpgAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { request ->
            authenticator.check(request) == AgentIpcAuthentication.ACCEPTED
        },
    ),
    allowAuthenticate = true,
    onClose = authenticator::close,
)

private fun gpgAgentPacketProtocol(
    rpcHandler: GpgAgentRpcHandler,
    allowAuthenticate: Boolean,
    maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
    onOversizeResponse: (size: Int) -> Unit = {},
    onClose: () -> Unit = {},
) = AgentRpcPacketProtocol(
    decodeRequest = GpgAgentProtoCodec::decodeRequest,
    encodeResponse = GpgAgentProtoCodec::encodeResponse,
    processRequest = rpcHandler::processRequest,
    errorResponse = { id, error -> GpgAgentMessages.IpcResponse(id = id, error = error) },
    allowAuthenticate = allowAuthenticate,
    maxPacketSize = maxPacketSize,
    onOversizeResponse = onOversizeResponse,
    onClose = onClose,
)
