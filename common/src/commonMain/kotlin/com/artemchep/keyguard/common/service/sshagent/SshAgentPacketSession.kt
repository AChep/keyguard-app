package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthentication
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcFraming
import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.agent.AgentRpcPacketProtocol

/** One connection's transport-independent SSH IPC authentication and dispatch. */
class SshAgentPacketSession private constructor(
    protocol: AgentRpcPacketProtocol<SshAgentMessages.IpcRequest, SshAgentMessages.IpcResponse>,
) : AgentPacketSession<SshAgentMessages.IpcRequest, SshAgentMessages.IpcResponse>(
    protocol = protocol,
    authenticated = !protocol.allowAuthenticate,
) {
    /** A session that authenticates the agent with its launch [authToken]. */
    constructor(
        requestProcessor: SshAgentRequestProcessor,
        authToken: ByteArray,
    ) : this(sshAgentPacketProtocol(requestProcessor, AgentIpcAuthenticator(authToken)))

    /**
     * A session of [rpcHandler]. Where the transport has authenticated the
     * agent, the session starts authenticated and refuses another handshake.
     * A response over [maxPacketSize] is answered with an error.
     */
    internal constructor(
        rpcHandler: SshAgentRpcHandler,
        authenticatedByTransport: Boolean,
        maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
        onOversizeResponse: (size: Int) -> Unit = {},
    ) : this(
        sshAgentPacketProtocol(
            rpcHandler = rpcHandler,
            allowAuthenticate = !authenticatedByTransport,
            maxPacketSize = maxPacketSize,
            onOversizeResponse = onOversizeResponse,
        ),
    )
}

private fun sshAgentPacketProtocol(
    requestProcessor: SshAgentRequestProcessor,
    authenticator: AgentIpcAuthenticator,
) = sshAgentPacketProtocol(
    rpcHandler = SshAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { request ->
            authenticator.check(request) == AgentIpcAuthentication.ACCEPTED
        },
    ),
    allowAuthenticate = true,
    onClose = authenticator::close,
)

private fun sshAgentPacketProtocol(
    rpcHandler: SshAgentRpcHandler,
    allowAuthenticate: Boolean,
    maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
    onOversizeResponse: (size: Int) -> Unit = {},
    onClose: () -> Unit = {},
) = AgentRpcPacketProtocol(
    decodeRequest = SshAgentProtoCodec::decodeRequest,
    encodeResponse = SshAgentProtoCodec::encodeResponse,
    processRequest = rpcHandler::processRequest,
    errorResponse = { id, error -> SshAgentMessages.IpcResponse(id = id, error = error) },
    allowAuthenticate = allowAuthenticate,
    maxPacketSize = maxPacketSize,
    onOversizeResponse = onOversizeResponse,
    onClose = onClose,
)
