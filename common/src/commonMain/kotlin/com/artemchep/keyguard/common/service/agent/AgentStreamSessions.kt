package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.util.useAndClear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serves this session over [stream] until the stream ends, a write gives up or
 * a reply closes the session. With an [ioTimeoutMs], each read, including the
 * wait for the next request, and each write gives up after that long and ends
 * the session. Every packet and reply is zeroed; transport failures propagate
 * as they are.
 */
suspend fun AgentPacketSession<*, *>.serveFramed(
    stream: AgentByteStream,
    ioTimeoutMs: Long? = null,
) {
    val channel = FramedAgentPacketChannel(stream, maxPacketSize)
    serve(
        readPacket = {
            withIoTimeout(ioTimeoutMs, discard = ::wipePacket) { channel.readPacket() }
        },
        writePacket = { packet ->
            withIoTimeout(ioTimeoutMs) { channel.writePacket(packet) } != null
        },
    )
}

/**
 * Serves one connection of the CLI agent: authentication, then exactly one
 * request. Input or the end of the stream while that request is processed
 * cancels the processing, and the connection ends quietly. With an
 * [ioTimeoutMs], each read, processing and write gives up after that long and
 * ends the connection. Every packet and reply is zeroed; transport failures
 * propagate as they are.
 *
 * The caller owns the session behind [process] and closes it.
 */
suspend fun serveCliAgentConnection(
    stream: AgentByteStream,
    maxPacketSize: Int,
    process: suspend (ByteArray) -> AgentPacketReply,
    ioTimeoutMs: Long? = null,
) {
    val channel = FramedAgentPacketChannel(stream, maxPacketSize)
    if (!channel.authenticate(process, ioTimeoutMs)) {
        return
    }
    val request = withIoTimeout(ioTimeoutMs, discard = ::wipePacket) { channel.readPacket() }
        ?: return
    request.useAndClear {
        channel.respondWhileConnected(stream, it, process, ioTimeoutMs)
    }
}

/** Reads and answers the first request. Returns whether the connection goes on. */
private suspend fun FramedAgentPacketChannel.authenticate(
    process: suspend (ByteArray) -> AgentPacketReply,
    ioTimeoutMs: Long?,
): Boolean {
    val packet = withIoTimeout(ioTimeoutMs, discard = ::wipePacket) { readPacket() }
        ?: return false
    val reply = packet.useAndClear {
        withIoTimeout(ioTimeoutMs, discard = ::wipeReply) { process(it) }
    }
    return reply != null && writeReply(reply, ioTimeoutMs) && !reply.close
}

/**
 * Processes [request] while watching the connection, then writes the response.
 * Input or the end of the stream cancels the processing. Cancelling the watch
 * closes the connection, so it ends only after the final write.
 */
private suspend fun FramedAgentPacketChannel.respondWhileConnected(
    stream: AgentByteStream,
    request: ByteArray,
    process: suspend (ByteArray) -> AgentPacketReply,
    ioTimeoutMs: Long?,
) {
    // A response that is never written is zeroed as well.
    var produced: AgentPacketReply? = null
    try {
        coroutineScope {
            val operation = async {
                process(request).also { produced = it }
            }
            val observer = launch {
                stream.awaitInputOrDisconnect()
                operation.cancel()
            }
            try {
                val response = awaitResponse(operation, ioTimeoutMs)
                if (response != null) {
                    writeReply(response, ioTimeoutMs)
                }
            } finally {
                observer.cancel()
                operation.cancel()
            }
        }
    } finally {
        // The scope has waited for the operation, so nothing sets it any more.
        produced?.let(::wipeReply)
    }
}

/** The response of [operation], or null when the watch cancelled it or the time ran out. */
private suspend fun awaitResponse(
    operation: Deferred<AgentPacketReply>,
    ioTimeoutMs: Long?,
): AgentPacketReply? = try {
    withIoTimeout(ioTimeoutMs) { operation.await() }
} catch (e: CancellationException) {
    // A cancelled caller is not a cancelled operation.
    currentCoroutineContext().ensureActive()
    if (!operation.isCancelled) {
        throw e
    }
    null
}

/** Writes [reply], then zeroes it. Returns false when the write gives up. */
private suspend fun FramedAgentPacketChannel.writeReply(
    reply: AgentPacketReply,
    ioTimeoutMs: Long?,
): Boolean = try {
    withIoTimeout(ioTimeoutMs) { writePacket(reply.packet) } != null
} finally {
    wipeReply(reply)
}

/**
 * Runs [block], or gives up after [timeoutMs] and returns null. A result that
 * the time limit or a cancellation drops on its way out goes to [discard].
 */
private suspend fun <T : Any> withIoTimeout(
    timeoutMs: Long?,
    discard: (T) -> Unit = {},
    block: suspend () -> T?,
): T? {
    if (timeoutMs == null) {
        return block()
    }
    var produced: T? = null
    var result: T? = null
    try {
        result = withTimeoutOrNull(timeoutMs) {
            block().also { produced = it }
        }
    } finally {
        if (result == null) {
            produced?.let(discard)
        }
    }
    return result
}

private fun wipePacket(packet: ByteArray) = packet.fill(0)

private fun wipeReply(reply: AgentPacketReply) = reply.packet.fill(0)
