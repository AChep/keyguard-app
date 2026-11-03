package com.artemchep.keyguard.util.agentipc

import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

internal val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

internal actual fun agentIpcTestLocation(exactName: String?): AgentIpcLocation = if (isWindows) {
    AgentIpcLocation.WindowsPipe("kg-ipc-test-", exactName)
} else {
    // Short, for the socket path limits of Linux and macOS.
    AgentIpcLocation.UnixDirectory("/tmp", "kg-ipc-test-", exactName)
}

internal actual fun agentIpcForeignLocation(): AgentIpcLocation = if (isWindows) {
    AgentIpcLocation.UnixDirectory("/tmp", "kg-ipc-test-")
} else {
    AgentIpcLocation.WindowsPipe("kg-ipc-test-")
}

internal actual fun connectAgentIpcTestClient(endpoint: String): AgentIpcTestClient {
    if (!isWindows) return SocketTestClient(openSocket(endpoint))
    // A pipe instance is busy while another client connects to it; helpers retry
    // the same way.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (true) {
        try {
            return PipeTestClient(RandomAccessFile(endpoint, "rw"))
        } catch (error: FileNotFoundException) {
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(2)
        }
    }
}

internal actual fun tryConnectAgentIpcTestClient(endpoint: String): AgentIpcTestClient? = try {
    if (isWindows) PipeTestClient(RandomAccessFile(endpoint, "rw")) else SocketTestClient(openSocket(endpoint))
} catch (_: IOException) {
    null
}

private fun openSocket(endpoint: String): SocketChannel {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try {
        channel.connect(UnixDomainSocketAddress.of(endpoint))
    } catch (error: IOException) {
        channel.close()
        throw error
    }
    return channel
}

private class SocketTestClient(
    private val channel: SocketChannel,
) : AgentIpcTestClient {
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        channel.read(ByteBuffer.wrap(buffer, offset, length))
    } catch (_: IOException) {
        // A reset: queued connections of a closed listener end this way.
        -1
    }

    override fun close() = channel.close()
}

/** A synchronous pipe handle: it must not read and write at the same time. */
private class PipeTestClient(
    private val file: RandomAccessFile,
) : AgentIpcTestClient {
    override fun write(bytes: ByteArray) = file.write(bytes)

    // A broken pipe reads as the end of the stream.
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        file.read(buffer, offset, length)
    } catch (_: IOException) {
        -1
    }

    override fun close() = file.close()
}
