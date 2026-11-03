package com.artemchep.keyguard.common.service.agent

/** An SSH or GPG agent request, whose envelope carries the shared handshake. */
interface AgentRpcRequest {
    val id: Long
    val authenticate: AgentIpcMessages.AuthenticateRequest?
}

/** An SSH or GPG agent response, whose envelope carries the shared handshake. */
interface AgentRpcResponse {
    val authenticate: AgentIpcMessages.AuthenticateResponse?
}

/**
 * The [AgentPacketProtocol] of the SSH and GPG agents. An authenticate
 * request decides the session where the transport allows one, and any other
 * request keeps it as it is. A response too large for the transport is
 * answered with an error, so the session stays open.
 */
internal class AgentRpcPacketProtocol<Req : AgentRpcRequest, Res : AgentRpcResponse>(
    private val decodeRequest: (ByteArray) -> Req,
    private val encodeResponse: (Res) -> ByteArray,
    private val processRequest: suspend (Req, AgentRpcRequestContext) -> Res,
    /** The envelope that answers the request of an id with an error. */
    private val errorResponse: (Long, AgentIpcMessages.ErrorResponse) -> Res,
    /** False where the transport authenticated the helper, as on Android. */
    val allowAuthenticate: Boolean,
    /** The largest packet the transport carries, in either direction. */
    override val maxPacketSize: Int = AgentIpcFraming.MAX_PACKET_SIZE,
    /** Reports the size of a response that the transport could not carry. */
    private val onOversizeResponse: (size: Int) -> Unit = {},
    private val onClose: () -> Unit = {},
) : AgentPacketProtocol<Req, Res> {
    override fun decode(packet: ByteArray) = decodeRequest(packet)

    override fun encode(response: Res) = encodeResponse(response)

    override suspend fun handle(
        request: Req,
        authenticated: Boolean,
    ) = processRequest(
        request,
        AgentRpcRequestContext(
            authenticated = authenticated,
            allowAuthenticate = allowAuthenticate,
        ),
    )

    override fun authenticatedAfter(
        request: Req,
        response: Res,
        authenticated: Boolean,
    ): Boolean? = if (allowAuthenticate && request.authenticate != null) {
        response.authenticate?.success == true
    } else {
        null
    }

    override fun wipeRequest(request: Req) {
        request.authenticate?.token?.fill(0)
    }

    override fun oversizeResponse(request: Req, size: Int): Res {
        onOversizeResponse(size)
        return errorResponse(
            request.id,
            AgentIpcMessages.ErrorResponse(
                message = "Response of $size bytes exceeds the $maxPacketSize-byte limit of this connection",
                code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
            ),
        )
    }

    override fun close() = onClose()
}
