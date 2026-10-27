@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.agentipc

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_UNIX
import platform.posix.EINTR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.connect
import platform.posix.errno
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.close as closeDescriptor

internal actual fun agentIpcTestLocation(exactName: String?): AgentIpcLocation =
    // Short, for the 103-byte socket path limit of macOS.
    AgentIpcLocation.UnixDirectory("/tmp", "kg-ipc-test-", exactName)

internal actual fun agentIpcForeignLocation(): AgentIpcLocation = AgentIpcLocation.WindowsPipe("kg-ipc-test-")

internal actual fun connectAgentIpcTestClient(endpoint: String): AgentIpcTestClient =
    checkNotNull(tryConnectAgentIpcTestClient(endpoint)) { "Could not connect to the endpoint" }

internal actual fun tryConnectAgentIpcTestClient(endpoint: String): AgentIpcTestClient? {
    val fd = socket(AF_UNIX, SOCK_STREAM, 0)
    check(fd >= 0) { "Could not create a socket" }
    val address = socketAddress(endpoint)
    // Per descriptor, never process-wide: a write to a closed peer fails with EPIPE.
    val connected = noSigpipe(fd) &&
        address.usePinned { connect(fd, it.addressOf(0).reinterpret<sockaddr>(), address.size.convert()) } == 0
    if (!connected) {
        closeDescriptor(fd)
        return null
    }
    return PosixTestClient(fd)
}

/** A `sockaddr_un` as bytes: its length, the family, the path and a NUL. */
private fun socketAddress(path: String): ByteArray {
    val bytes = path.encodeToByteArray()
    return ByteArray(bytes.size + SOCKET_ADDRESS_OVERHEAD).also {
        it[0] = it.size.toByte()
        it[1] = AF_UNIX.toByte()
        bytes.copyInto(it, 2)
    }
}

private fun noSigpipe(fd: Int): Boolean = memScoped {
    val enabled = alloc<IntVar> { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert()) == 0
}

/** A blocking Unix-domain socket client, as a helper connects. */
private class PosixTestClient(
    private val fd: Int,
) : AgentIpcTestClient {
    private val closed = atomic(false)

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { send(fd, it.addressOf(offset), (bytes.size - offset).convert(), 0) }
            check(count > 0L || errno == EINTR) { "Could not write to the endpoint" }
            if (count > 0L) offset += count.toInt()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count: Long
        do {
            count = buffer.usePinned { recv(fd, it.addressOf(offset), length.convert(), 0) }
        } while (count < 0L && errno == EINTR)
        // The end of the stream, or a reset: queued connections of a closed listener end so.
        return if (count > 0L) count.toInt() else -1
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeDescriptor(fd)
    }
}

private const val SOCKET_ADDRESS_OVERHEAD = 3
