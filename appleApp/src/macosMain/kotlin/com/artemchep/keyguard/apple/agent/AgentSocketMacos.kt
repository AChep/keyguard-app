package com.artemchep.keyguard.apple.agent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import platform.Foundation.NSUUID
import platform.posix.AF_UNIX
import platform.posix.EAGAIN
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.accept
import platform.posix.bind
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.geteuid
import platform.posix.getpeereid
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.stat
import platform.posix.unlink

/** A private Unix socket that serves only the launched helper. Nonblocking
 * descriptors are owned by their serving coroutine until cancellation
 * completes; stop never closes a descriptor while another coroutine is using it. */
@Suppress("TooGenericExceptionCaught") // Close owned native descriptors before propagating initialization failures.
@OptIn(ExperimentalForeignApi::class)
internal class AgentIpcListener(
    val path: String,
    private val maxConnections: Int,
) {
    private val fd: Int
    private val identity: FileIdentity

    init {
        requireSocketPath(path)
        val listener = socket(AF_UNIX, SOCK_STREAM, 0)
        check(listener >= 0) { "Could not create agent IPC socket" }
        var boundIdentity: FileIdentity? = null
        try {
            nonblocking(listener)
            noSigpipe(listener)
            val address = socketAddress(path)
            val bindResult = address.usePinned {
                bind(listener, it.addressOf(0).reinterpret<sockaddr>(), address.size.convert())
            }
            check(bindResult == 0) {
                "Could not bind private agent IPC socket"
            }
            boundIdentity = fileIdentity(path)
            check(chmod(path, PRIVATE_SOCKET_PERMISSIONS) == 0) { "Could not protect agent IPC socket" } // 0600
            check(listen(listener, SOCKET_BACKLOG) == 0) { "Could not listen on agent IPC socket" }
            identity = requireNotNull(boundIdentity)
            fd = listener
        } catch (e: Throwable) {
            close(listener)
            if (boundIdentity != null && fileIdentity(path) == boundIdentity) unlink(path)
            throw e
        }
    }

    /** Runs [handle] for each connection from the live [childPid] until cancelled.
     * The connection's descriptor is closed once [handle] returns. */
    @Suppress("LoopWithTooManyJumpStatements") // Each rejected descriptor is closed before the next accept.
    suspend fun serve(
        childPid: () -> Int?,
        onConnectionFailure: () -> Unit = {},
        handle: suspend (client: Int) -> Unit,
    ) = coroutineScope {
        val connections = Semaphore(maxConnections)
        while (true) {
            ensureActive()
            val client = accept(fd, null, null)
            if (client < 0) {
                val code = errno
                if (code == EAGAIN || code == EWOULDBLOCK) {
                    awaitReadable(fd)
                    continue
                }
                if (code == EINTR) continue
                error("Agent IPC listener failed")
            }
            if (!connections.tryAcquire()) {
                close(client)
                continue
            }
            // Enter the owning try/finally before cancellation can discard the
            // coroutine's first dispatch and leak an already accepted descriptor.
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    nonblocking(client)
                    noSigpipe(client)
                    // This precedes packet parsing and token authentication. A
                    // bearer token alone is insufficient to impersonate the child.
                    if (verifiedChildPeer(client, childPid())) handle(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onConnectionFailure()
                } finally {
                    close(client)
                    connections.release()
                }
            }
        }
    }

    /** Call only after serve and all its children have completed. */
    fun close() {
        close(fd)
        if (fileIdentity(path) == identity) unlink(path)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun verifiedChildPeer(fd: Int, expectedPid: Int?): Boolean = memScoped {
    if (expectedPid == null || expectedPid <= 0) return@memScoped false
    val pid = alloc<IntVar>()
    val length = alloc<UIntVar> { value = sizeOf<IntVar>().toUInt() }
    // LOCAL_PEEREPID (3), not LOCAL_PEERPID (2): use the effective connecting
    // process identity, matching the desktop transport and Rust parent check.
    val result = getsockopt(fd, 0, LOCAL_PEER_EFFECTIVE_PID, pid.ptr, length.ptr)
    if (result != 0 || length.value != sizeOf<IntVar>().toUInt()) return@memScoped false
    val uid = alloc<UIntVar>()
    val gid = alloc<UIntVar>()
    getpeereid(fd, uid.ptr, gid.ptr) == 0 && uid.value == geteuid() && pid.value == expectedPid
}

internal fun requireSocketPath(path: String) {
    require(path.startsWith('/') && '\u0000' !in path && path.encodeToByteArray().size <= MAX_UNIX_SOCKET_PATH_BYTES) {
        "The App Group path is too long for an agent socket"
    }
}

internal fun socketAddress(path: String): ByteArray {
    requireSocketPath(path)
    val bytes = path.encodeToByteArray()
    return ByteArray(bytes.size + SOCKET_ADDRESS_OVERHEAD).also {
        it[0] = it.size.toByte()
        it[1] = AF_UNIX.toByte()
        bytes.copyInto(it, 2)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nonblocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL)
    check(flags >= 0 && fcntl(fd, F_SETFL, flags or O_NONBLOCK) == 0 && fcntl(fd, F_SETFD, FD_CLOEXEC) == 0) {
        "Could not configure agent transport"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun noSigpipe(fd: Int) = memScoped {
    val enabled = alloc<IntVar> { value = 1 }
    check(setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert()) == 0)
}

internal data class FileIdentity(val device: Long, val inode: ULong)

@OptIn(ExperimentalForeignApi::class)
internal fun fileIdentity(path: String): FileIdentity? = memScoped {
    val info = alloc<stat>()
    if (lstat(path, info.ptr) != 0) return@memScoped null
    FileIdentity(info.st_dev.toLong(), info.st_ino.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun ensurePrivateDirectory(path: String) {
    require(path.startsWith('/') && '\u0000' !in path)
    if (mkdir(path, PRIVATE_DIRECTORY_PERMISSIONS) != 0 && errno != EEXIST) {
        error("Could not create private agent directory")
    }
    memScoped {
        val info = alloc<stat>()
        check(
            lstat(path, info.ptr) == 0 &&
                info.st_mode.toInt() and S_IFMT == S_IFDIR &&
                info.st_uid == geteuid() &&
                info.st_mode.toInt() and UNIX_PERMISSION_MASK == PRIVATE_DIRECTORY_PERMISSION_BITS,
        ) {
            "Agent directory must be owned by the current user with mode 0700 and must not be a symlink"
        }
    }
}

internal class PrivateIpcDirectory(val path: String, val identity: FileIdentity) {
    @OptIn(ExperimentalForeignApi::class)
    fun removeIfOwned() { if (fileIdentity(path) == identity) rmdir(path) }
}

@OptIn(ExperimentalForeignApi::class)
internal fun createPrivateIpcDirectory(parent: String): PrivateIpcDirectory {
    repeat(DIRECTORY_CREATE_ATTEMPTS) {
        val path = "$parent/${NSUUID().UUIDString.replace("-", "").take(12)}"
        requireSocketPath("$path/i")
        if (mkdir(
            path,
            PRIVATE_DIRECTORY_PERMISSIONS,
        ) == 0) return PrivateIpcDirectory(
            path,
            requireNotNull(fileIdentity(path)),
        )
        check(errno == EEXIST) { "Could not create private agent IPC directory" }
    }
    error("Could not allocate private agent IPC directory")
}

private const val PRIVATE_SOCKET_PERMISSIONS: UShort = 0x180u

private const val SOCKET_BACKLOG = 8

private const val LOCAL_PEER_EFFECTIVE_PID = 3

private const val MAX_UNIX_SOCKET_PATH_BYTES = 103

private const val SOCKET_ADDRESS_OVERHEAD = 3

private const val PRIVATE_DIRECTORY_PERMISSIONS: UShort = 0x1c0u

private const val PRIVATE_DIRECTORY_PERMISSION_BITS = 0x1c0

private const val UNIX_PERMISSION_MASK = 0x1ff

private const val DIRECTORY_CREATE_ATTEMPTS = 10
