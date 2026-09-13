package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentPacketChannel
import com.artemchep.keyguard.common.service.agent.runAgentPacketSession
import com.artemchep.keyguard.util.foundation.constantTimeEquals

/** One connection's transport-independent GPG IPC authentication and dispatch. */
class GpgAgentPacketSession(
    requestProcessor: GpgAgentRequestProcessor,
    authToken: ByteArray,
) {
    companion object {
        const val MAX_PACKET_SIZE = 16 * 1024 * 1024
        const val AUTH_TOKEN_SIZE = 32
    }

    init {
        require(authToken.size == AUTH_TOKEN_SIZE) { "GPG IPC authentication requires a 32-byte token" }
    }

    private val expectedAuthToken = authToken.copyOf()
    private var authenticated = false
    private var closed = false
    private val rpcHandler = GpgAgentRpcHandler(
        requestProcessor = requestProcessor,
        authenticate = { request ->
            val tokenMatches = expectedAuthToken.constantTimeEquals(request.token)
            tokenMatches && request.protocolRevision == GpgAgentMessages.PROTOCOL_REVISION
        },
    )

    /**
     * Processes one protobuf payload, without its four-byte length prefix.
     *
     * The transport must verify the helper's kernel peer identity before
     * creating this session and serialize calls on each connection. Its reads
     * and writes can suspend independently of request processing. Malformed
     * input throws and closes this session; authentication failure returns a
     * final response that must be sent before closing the connection.
     */
    // Cleanup must run for every failure, including cancellation, before the original error is rethrown.
    @Suppress("TooGenericExceptionCaught")
    suspend fun process(packet: ByteArray): GpgAgentPacketReply {
        check(!closed) { "GPG IPC session is closed" }
        return try {
            require(packet.size in 1..MAX_PACKET_SIZE) { "Invalid GPG IPC packet size" }
            val request = GpgAgentProtoCodec.decodeRequest(packet)
            val response = try {
                rpcHandler.processRequest(
                    request = request,
                    context = GpgAgentRpcRequestContext(
                        authenticated = authenticated,
                        allowAuthenticate = true,
                    ),
                )
            } finally {
                request.authenticate?.token?.fill(0)
            }
            val close = request.authenticate != null && response.authenticate?.success != true
            if (request.authenticate != null) {
                authenticated = response.authenticate?.success == true
            }
            val responsePacket = GpgAgentProtoCodec.encodeResponse(response)
            require(responsePacket.size in 1..MAX_PACKET_SIZE) { "Invalid GPG IPC response size" }
            if (close) close()
            GpgAgentPacketReply(packet = responsePacket, close = close)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Erases the session's token after processing has finished or been cancelled. */
    fun close() {
        closed = true
        authenticated = false
        expectedAuthToken.fill(0)
    }
}

/** Encoded response and whether the transport must close after writing it. */
data class GpgAgentPacketReply(
    val packet: ByteArray,
    val close: Boolean,
)

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
