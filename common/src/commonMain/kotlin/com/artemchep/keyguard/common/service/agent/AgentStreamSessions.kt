package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.util.useAndClear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [ioTimeoutMs], each read and write gives up after that long and ends the
 * connection; processing the request has no time limit, as an approval prompt
 * owns its deadline. Every packet and reply is zeroed; transport failures
 * propagate as they are.
 *
 * The reply to the request may turn the connection into a stream, its
 * [upgrade][AgentPacketReply.upgrade]: once the reply is written, the
 * connection serves the stream until either side ends it. Its reads wait for
 * the peer without a time limit; its writes keep the [ioTimeoutMs]. A stream
 * is closed whether or not it was served; the stream of the authentication's
 * reply is never served.
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

/**
 * The input of [source] while its request is processed. A wait for input
 * ends only with input, the end of the stream, or by closing the connection,
 * so it runs on its own in [scope]: cancelling an [await] only stops that
 * watch. The reads of [stream] start once the wait has returned, as the input
 * takes one call at a time.
 */
private class WatchedInput(
    private val source: AgentByteStream,
    scope: CoroutineScope,
) {
    private val wait = scope.launch { source.awaitInputOrDisconnect() }

    /** The [source], whose reads start once the wait has returned. */
    val stream = AgentByteStream(
        read = { buffer, offset, length ->
            wait.join()
            source.read(buffer, offset, length)
        },
        write = source.write,
        awaitInputOrDisconnect = {
            wait.join()
            source.awaitInputOrDisconnect()
        },
    )

    /** Returns once input or the end of the stream arrives. */
    suspend fun await() = wait.join()

    /** Ends a wait that is still pending, which closes the connection. */
    fun close() = wait.cancel()
}

/** The packets of a connection that became a stream. */
private class FramedAgentDuplexChannel(
    stream: AgentByteStream,
    maxPacketSize: Int,
    private val ioTimeoutMs: Long?,
) : AgentDuplexChannel {
    private val channel = FramedAgentPacketChannel(stream, maxPacketSize)
    private val writeLock = Mutex()

    override suspend fun readPacket(): ByteArray? = channel.readPacket()

    override suspend fun writePacket(packet: ByteArray): Boolean = writeLock.withLock {
        withIoTimeout(ioTimeoutMs) { channel.writePacket(packet) } != null
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
        withIoTimeout(ioTimeoutMs, discard = ::discardReply) { process(it) }
    }
    return try {
        reply != null && writeReply(reply, ioTimeoutMs) && !reply.close
    } finally {
        reply?.upgrade?.close()
    }
}

/**
 * Processes [request] while watching the connection, then writes the response
 * and serves the stream that it opens, if any. Input or the end of the stream
 * cancels the processing. The watch stops once the response arrives, and
 * leaves the input to the stream; the connection closes only after the final
 * write, or after the stream.
 */
private suspend fun FramedAgentPacketChannel.respondWhileConnected(
    stream: AgentByteStream,
    request: ByteArray,
    process: suspend (ByteArray) -> AgentPacketReply,
    ioTimeoutMs: Long?,
) {
    // A response that is never written is discarded as well.
    var produced: AgentPacketReply? = null
    try {
        coroutineScope {
            val input = WatchedInput(stream, scope = this)
            val operation = async {
                process(request).also { produced = it }
            }
            val watch = launch {
                input.await()
                operation.cancel()
            }
            try {
                val response = awaitResponse(operation)
                watch.cancel()
                if (response != null) {
                    produced = null
                    replyThenServe(response, input.stream, ioTimeoutMs)
                }
            } finally {
                operation.cancel()
                input.close()
            }
        }
    } finally {
        // The scope has waited for the operation, so nothing sets it any more.
        produced?.let(::discardReply)
    }
}

/**
 * Writes [reply], then serves its [upgrade][AgentPacketReply.upgrade] over
 * [stream], if any. Closes the upgrade whether or not it was served.
 */
private suspend fun FramedAgentPacketChannel.replyThenServe(
    reply: AgentPacketReply,
    stream: AgentByteStream,
    ioTimeoutMs: Long?,
) {
    val upgrade = reply.upgrade
    try {
        val answered = writeReply(reply, ioTimeoutMs) && !reply.close
        if (answered && upgrade != null) {
            upgrade.serve(FramedAgentDuplexChannel(stream, upgrade.maxPacketSize, ioTimeoutMs))
        }
    } finally {
        upgrade?.close()
    }
}

/** The response of [operation], or null when the watch cancelled it. */
private suspend fun awaitResponse(
    operation: Deferred<AgentPacketReply>,
): AgentPacketReply? = try {
    operation.await()
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

/** Zeroes a reply that is never written, and closes its upgrade. */
private fun discardReply(reply: AgentPacketReply) {
    wipeReply(reply)
    reply.upgrade?.close()
}
