package com.artemchep.keyguard.common.service.agent

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.lang.reflect.Field
import java.nio.channels.SocketChannel
import com.sun.jna.Platform as JnaPlatform

/** Kernel-authenticated identity of a Unix-domain-socket peer. */
internal data class UnixAgentPeerCredentials(
    val pid: Long,
    val uid: Long,
)

internal fun currentUnixEffectiveUid(): Long = UnixLibC.INSTANCE.effectiveUid()

/**
 * Verifies that an accepted Unix-domain-socket connection belongs to the
 * exact helper process spawned by [AgentManager].
 *
 * The PID and effective UID are sampled from the connected socket itself;
 * request payloads and process-supplied metadata are deliberately not used.
 */
internal fun verifyUnixAgentPeer(
    channel: SocketChannel,
    expectedProcess: Process,
) {
    check(expectedProcess.isAlive) {
        "Expected agent process ${expectedProcess.pid()} is no longer alive"
    }

    val peer = readUnixAgentPeerCredentials(channel)
    val expectedPid = expectedProcess.pid()
    val expectedUid = currentUnixEffectiveUid()
    check(peer.pid == expectedPid) {
        "IPC peer PID mismatch: expected $expectedPid, got ${peer.pid}"
    }
    check(peer.uid == expectedUid) {
        "IPC peer UID mismatch: expected $expectedUid, got ${peer.uid}"
    }

    // Close the small PID-reuse window around credential sampling. A live
    // java.lang.Process still refers to the exact child handle spawned by us.
    check(expectedProcess.isAlive) {
        "Expected agent process $expectedPid exited during peer verification"
    }
}

/**
 * Reads PID and effective UID from an accepted Unix-domain socket.
 *
 * Java exposes only user/group names through `SO_PEERCRED`, not the peer PID,
 * so this uses the native socket descriptor and the platform credential API:
 * Linux `SO_PEERCRED`, or macOS `LOCAL_PEEREPID` plus `getpeereid`.
 */
internal fun readUnixAgentPeerCredentials(
    channel: SocketChannel,
): UnixAgentPeerCredentials {
    val fd = SocketChannelFd.value(channel)
    return when {
        JnaPlatform.isLinux() -> UnixLibC.INSTANCE.linuxPeerCredentials(fd)
        JnaPlatform.isMac() -> UnixLibC.INSTANCE.macosPeerCredentials(fd)
        else -> throw IOException(
            "IPC peer credential verification is unsupported on ${System.getProperty("os.name")}",
        )
    }
}

private object SocketChannelFd {
    private val unsafeAccess: UnsafeAccess by lazy(::loadUnsafeAccess)

    fun value(channel: SocketChannel): Int {
        // Some runtimes export getFDVal. Standard JDK 21 does not export the
        // implementation package, so fall back to a narrowly scoped Unsafe
        // field read. Any runtime-layout change fails closed.
        runCatching {
            val method = channel.javaClass.getMethod("getFDVal")
            return (method.invoke(channel) as Number).toInt()
        }

        val field = findField(channel.javaClass, "fdVal")
            ?: throw IOException("Could not locate the Unix socket descriptor")
        val fd = unsafeAccess.getInt(channel, field)
        if (fd < 0) {
            throw IOException("Unix socket descriptor is closed")
        }
        return fd
    }

    private fun findField(
        initialClass: Class<*>,
        name: String,
    ): Field? {
        var current: Class<*>? = initialClass
        while (current != null) {
            val currentClass = current
            val field = runCatching { currentClass.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun loadUnsafeAccess(): UnsafeAccess {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val objectFieldOffset = unsafeClass.getMethod(
                "objectFieldOffset",
                Field::class.java,
            )
            val getInt = unsafeClass.getMethod(
                "getInt",
                Any::class.java,
                Long::class.javaPrimitiveType,
            )
            return UnsafeAccess(
                objectFieldOffset = { field ->
                    (objectFieldOffset.invoke(unsafe, field) as Number).toLong()
                },
                readInt = { target, offset ->
                    (getInt.invoke(unsafe, target, offset) as Number).toInt()
                },
            )
        } catch (e: ReflectiveOperationException) {
            throw IOException("Could not access the Unix socket descriptor", e)
        }
    }

    private data class UnsafeAccess(
        val objectFieldOffset: (Field) -> Long,
        val readInt: (Any, Long) -> Int,
    ) {
        fun getInt(
            target: Any,
            field: Field,
        ): Int = readInt(target, objectFieldOffset(field))
    }
}

@Suppress("FunctionName")
private interface UnixLibC : Library {
    companion object {
        private const val LINUX_SOL_SOCKET = 1
        private const val LINUX_SO_PEERCRED = 17
        private const val MACOS_SOL_LOCAL = 0
        private const val MACOS_LOCAL_PEEREPID = 3
        private const val PID_SIZE = 4
        private const val LINUX_UCRED_SIZE = 12

        val INSTANCE: UnixLibC by lazy {
            Native.load(
                JnaPlatform.C_LIBRARY_NAME,
                UnixLibC::class.java,
            ) as UnixLibC
        }
    }

    fun getsockopt(
        socket: Int,
        level: Int,
        optionName: Int,
        optionValue: Memory,
        optionLength: IntByReference,
    ): Int

    fun getpeereid(
        socket: Int,
        effectiveUid: IntByReference,
        effectiveGid: IntByReference,
    ): Int

    fun geteuid(): Int

    fun effectiveUid(): Long = Integer.toUnsignedLong(geteuid())

    fun linuxPeerCredentials(socket: Int): UnixAgentPeerCredentials {
        val credentials = Memory(LINUX_UCRED_SIZE.toLong())
        val length = IntByReference(LINUX_UCRED_SIZE)
        if (
            getsockopt(
                socket,
                LINUX_SOL_SOCKET,
                LINUX_SO_PEERCRED,
                credentials,
                length,
            ) != 0 ||
            length.value != LINUX_UCRED_SIZE
        ) {
            throw unixCallException("getsockopt(SO_PEERCRED)")
        }

        val pid = credentials.getInt(0).toLong()
        if (pid <= 0) {
            throw IOException("getsockopt(SO_PEERCRED) returned an invalid PID: $pid")
        }
        return UnixAgentPeerCredentials(
            pid = pid,
            uid = Integer.toUnsignedLong(credentials.getInt(4)),
        )
    }

    fun macosPeerCredentials(socket: Int): UnixAgentPeerCredentials {
        val pidMemory = Memory(PID_SIZE.toLong())
        val pidLength = IntByReference(PID_SIZE)
        if (
            getsockopt(
                socket,
                MACOS_SOL_LOCAL,
                MACOS_LOCAL_PEEREPID,
                pidMemory,
                pidLength,
            ) != 0 ||
            pidLength.value != PID_SIZE
        ) {
            throw unixCallException("getsockopt(LOCAL_PEEREPID)")
        }

        val uid = IntByReference()
        val gid = IntByReference()
        if (getpeereid(socket, uid, gid) != 0) {
            throw unixCallException("getpeereid")
        }

        val pid = pidMemory.getInt(0).toLong()
        if (pid <= 0) {
            throw IOException("getsockopt(LOCAL_PEEREPID) returned an invalid PID: $pid")
        }
        return UnixAgentPeerCredentials(
            pid = pid,
            uid = Integer.toUnsignedLong(uid.value),
        )
    }

    private fun unixCallException(function: String): IOException =
        IOException("$function failed with native error ${Native.getLastError()}")
}
