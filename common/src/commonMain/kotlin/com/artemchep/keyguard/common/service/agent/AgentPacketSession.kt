package com.artemchep.keyguard.common.service.agent

/** One agent's messages, as an [AgentPacketSession] serves them. */
internal interface AgentPacketProtocol<Req : Any, Res : Any> {
    val maxPacketSize: Int

    fun decode(packet: ByteArray): Req

    fun encode(response: Res): ByteArray

    suspend fun handle(request: Req, authenticated: Boolean): Res

    /**
     * The authentication of the session once [response] answers [request]:
     * null keeps it, true authenticates the session, and false closes it
     * after the reply.
     */
    fun authenticatedAfter(request: Req, response: Res, authenticated: Boolean): Boolean?

    /** Zeroes what [request] can hold, once it is handled. */
    fun wipeRequest(request: Req) = Unit

    /** Zeroes what [response] can hold, once it is encoded. */
    fun wipeResponse(response: Res) = Unit

    /**
     * The reply to [request] when its response encodes to [size] bytes, more
     * than [maxPacketSize]. Null closes the session, as for malformed input.
     */
    fun oversizeResponse(request: Req, size: Int): Res? = null

    /** Releases what the protocol holds, such as a token, when the session closes. */
    fun close() = Unit
}

/** An encoded response, and whether the transport must close after writing it. */
class AgentPacketReply(
    val packet: ByteArray,
    val close: Boolean,
)

/**
 * One connection's transport-independent authentication and dispatch.
 *
 * The transport must verify the helper's kernel peer identity before creating
 * the session and serialize calls on each connection. Its reads and writes can
 * suspend independently of request processing. Malformed input throws and
 * closes the session; a failed authentication returns a final reply that must
 * be sent before closing the connection. A response too large for the
 * transport is replaced by the protocol's oversize reply, if it has one.
 */
open class AgentPacketSession<Req : Any, Res : Any> internal constructor(
    private val protocol: AgentPacketProtocol<Req, Res>,
    authenticated: Boolean = false,
) {
    private var authenticated = authenticated
    private var closed = false

    /** The largest packet the transport may pass to [process] and send back. */
    val maxPacketSize: Int get() = protocol.maxPacketSize

    /** Processes one payload, without its four-byte length prefix. */
    // Cleanup must run for every failure, including cancellation, before the original error is rethrown.
    @Suppress("TooGenericExceptionCaught")
    suspend fun process(packet: ByteArray): AgentPacketReply {
        check(!closed) { "Agent IPC session is closed" }
        return try {
            require(AgentIpcFraming.isValidSize(packet.size, protocol.maxPacketSize)) {
                "Invalid agent IPC packet size"
            }
            val request = protocol.decode(packet)
            val response = try {
                protocol.handle(request, authenticated)
            } finally {
                protocol.wipeRequest(request)
            }
            val next = protocol.authenticatedAfter(request, response, authenticated)
            var encoded = encode(response)
            // A reply that changes the authentication is never replaced.
            if (encoded.size > protocol.maxPacketSize && next == null) {
                val fallback = protocol.oversizeResponse(request, encoded.size)
                if (fallback != null) {
                    encoded.fill(0)
                    encoded = encode(fallback)
                }
            }
            require(AgentIpcFraming.isValidSize(encoded.size, protocol.maxPacketSize)) {
                "Invalid agent IPC response size"
            }
            when (next) {
                true -> authenticated = true
                false -> close()
                null -> Unit
            }
            AgentPacketReply(packet = encoded, close = next == false)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun encode(response: Res): ByteArray = try {
        protocol.encode(response)
    } finally {
        protocol.wipeResponse(response)
    }

    /** Erases what the session holds after processing has finished or been cancelled. */
    fun close() {
        if (closed) return
        closed = true
        authenticated = false
        protocol.close()
    }
}

/**
 * Serves this session until [readPacket] ends it, [writePacket] fails or a
 * reply closes it. Zeroes every packet once it is processed or sent.
 */
suspend fun AgentPacketSession<*, *>.serve(
    readPacket: suspend () -> ByteArray?,
    writePacket: suspend (ByteArray) -> Boolean,
) {
    try {
        while (true) {
            val packet = readPacket()
                ?: break
            val reply = try {
                process(packet)
            } finally {
                packet.fill(0)
            }
            try {
                if (!writePacket(reply.packet) || reply.close) break
            } finally {
                reply.packet.fill(0)
            }
        }
    } finally {
        close()
    }
}
