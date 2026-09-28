package com.artemchep.keyguard.apple.gpgagent

import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileDescriptor
import platform.posix.F_SETNOSIGPIPE
import platform.posix.rmdir
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPacketSession
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import platform.Foundation.NSBundle
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSPipe
import platform.Foundation.NSTask
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.AF_UNIX
import platform.posix.EAGAIN
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGKILL
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.accept
import platform.posix.bind
import platform.posix.chmod
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.geteuid
import platform.posix.getpeereid
import platform.posix.getpid
import platform.posix.getsockopt
import platform.posix.kill
import platform.posix.listen
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.stat
import platform.posix.symlink
import platform.posix.unlink
import platform.posix.write

internal actual fun createGpgAgentRuntime(): GpgAgentRuntime = MacosGpgAgentRuntime()

/** Only the main application controller starts this runtime. It never resolves a
 * fallback socket in the app's private container: external GnuPG cannot reach it. */
@OptIn(ExperimentalForeignApi::class)
internal class MacosGpgAgentRuntime(
    private val binaryPath: () -> String? = {
        NSBundle.mainBundle.pathForResource("keyguard-gpg-agent", ofType = null)
    },
    private val groupPath: () -> String? = ::appleAppGroupContainerPath,
) : GpgAgentRuntime {
    override val isBinaryAvailable: Boolean get() = binaryPath() != null
    // This path belongs to the external user's shell, not NSHomeDirectory() in
    // the sandbox. Setup expands HOME in Terminal and queries its own gpgconf.
    override val gpgHome: String get() = "~/.keyguard/gnupg"
    override val agentSocket: String? get() = groupPath()?.trimEnd('/')?.plus("/gpg/s")

    override suspend fun start(scope: CoroutineScope, config: GpgAgentRuntimeConfig): GpgAgentRuntimeHandle {
        require(config.authToken.size == GpgAgentPacketSession.AUTH_TOKEN_SIZE)
        val ready = CompletableDeferred<GpgAgentRuntimeHandle>()
        val token = config.authToken.copyOf()
        val lifetime = scope.launch(Dispatchers.Default) {
            try {
                runHelper(config, token, ready)
            } catch (e: CancellationException) {
                ready.cancel(e)
                throw e
            } catch (e: Exception) {
                if (!ready.completeExceptionally(e)) {
                    config.onTerminated(e.message)
                }
            } finally {
                token.fill(0)
            }
        }
        lifetime.invokeOnCompletion { cause ->
            token.fill(0)
            if (!ready.isCompleted) ready.completeExceptionally(
                cause ?: IllegalStateException("GPG agent stopped before startup"),
            )
        }
        return try {
            ready.await()
        } catch (e: Throwable) {
            lifetime.cancel()
            throw e
        }
    }

    private suspend fun runHelper(
        config: GpgAgentRuntimeConfig,
        token: ByteArray,
        ready: CompletableDeferred<GpgAgentRuntimeHandle>,
    ): Unit = coroutineScope {
        val binary = requireNotNull(binaryPath()) { "Bundled GPG agent is unavailable" }
        val group = requireNotNull(groupPath()) { "GPG agent requires a provisioned, writable App Group" }
        val directory = group.trimEnd('/') + "/gpg"
        val publicSocket = "$directory/s"
        requireSocketPath(publicSocket)
        ensurePrivateDirectory(directory)
        val locks = "$directory/locks"
        ensurePrivateDirectory(locks)
        val ipcDirectory = createPrivateIpcDirectory(directory)
        val task = NSTask()
        val stdin = NSPipe()
        val stdout = NSPipe()
        val stderr = NSPipe()
        var server: MacosGpgIpcServer? = null
        var serverJob: Job? = null
        var stderrJob: Job? = null
        try {
            // Binding is synchronous and complete before the child can connect.
            server = MacosGpgIpcServer(ipcDirectory.path + "/i", config, token) {
                task.processIdentifier.takeIf { task.isRunning() }
            }
            task.setExecutableURL(NSURL.fileURLWithPath(binary))
            task.setArguments(listOf(
                "--ipc-socket", server.path,
                "--parent-pid", getpid().toString(),
                "--gpg-socket", publicSocket,
                "--lifecycle-lock-dir", locks,
            ))
            task.setStandardInput(stdin)
            task.setStandardOutput(stdout)
            task.setStandardError(stderr)
            check(task.launchAndReturnError(null)) { "Could not launch the bundled GPG agent" }
            val inputFd = stdin.fileHandleForWriting.fileDescriptor
            val outputFd = stdout.fileHandleForReading.fileDescriptor
            val errorFd = stderr.fileHandleForReading.fileDescriptor
            nonblocking(inputFd)
            // Darwin's per-descriptor flag also protects pipes if the child dies
            // while the launch token is being written. Never alter SIGPIPE globally.
            check(fcntl(inputFd, F_SETNOSIGPIPE, 1) == 0) { "Could not protect the GPG launch pipe" }
            nonblocking(outputFd)
            nonblocking(errorFd)
            serverJob = launch { server.serve() }
            // Drain without retaining potentially sensitive/unbounded helper output.
            stderrJob = launch { drain(errorFd) }
            val tokenLine = (token.toHex() + "\n").encodeToByteArray()
            try {
                check(withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    check(writeFully(inputFd, tokenLine)) { "GPG agent closed its launch pipe" }
                    awaitReadiness(outputFd)
                    check(task.isRunning()) { "GPG agent exited during startup" }
                    true
                } == true) { "Timed out waiting for GPG agent readiness" }
            } finally {
                tokenLine.fill(0)
            }
            val lifetime = currentCoroutineContext().job
            ready.complete(object : GpgAgentRuntimeHandle {
                override val isRunning: Boolean get() = lifetime.isActive && task.isRunning()
                override val gpgHome: String get() = this@MacosGpgAgentRuntime.gpgHome
                override val agentSocket: String get() = publicSocket
                override fun stop() { lifetime.cancel() }
            })
            config.log("GPG agent ready")
            // NSTask retains and reaps this exact child; checking isRunning before
            // signaling it avoids accidentally signaling a reused PID.
            while (task.isRunning()) delay(POLL_DELAY_MS)
            error("GPG agent exited with status ${task.terminationStatus}")
        } finally {
            withContext(NonCancellable) {
                serverJob?.cancelAndJoin()
                stderrJob?.cancelAndJoin()
                server?.close()
                closeHandle(stdin.fileHandleForWriting)
                if (task.isRunning()) runCatching { task.terminate() }
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    while (task.isRunning()) delay(POLL_DELAY_MS)
                }
                if (task.isRunning()) kill(task.processIdentifier, SIGKILL)
                closeHandle(stdout.fileHandleForReading)
                closeHandle(stderr.fileHandleForReading)
                ipcDirectory.removeIfOwned()
            }
        }
    }
}

private const val STARTUP_TIMEOUT_MS = 10_000L
private const val IO_TIMEOUT_MS = 30_000L
private const val POLL_DELAY_MS = 20L

/** Nonblocking descriptors are owned by their serving coroutine until cancellation
 * completes; stop never closes a descriptor while another coroutine is using it. */
@OptIn(ExperimentalForeignApi::class)
internal class MacosGpgIpcServer(
    val path: String,
    private val config: GpgAgentRuntimeConfig,
    authToken: ByteArray,
    private val childPid: () -> Int?,
) {
    private val token = authToken.copyOf()
    private val fd: Int
    private val identity: FileIdentity

    init {
        requireSocketPath(path)
        val listener = socket(AF_UNIX, SOCK_STREAM, 0)
        check(listener >= 0) { "Could not create GPG IPC socket" }
        var boundIdentity: FileIdentity? = null
        try {
            nonblocking(listener)
            noSigpipe(listener)
            val address = socketAddress(path)
            val bindResult = address.usePinned {
                bind(listener, it.addressOf(0).reinterpret<sockaddr>(), address.size.convert())
            }
            check(bindResult == 0) {
                "Could not bind private GPG IPC socket"
            }
            boundIdentity = fileIdentity(path)
            check(chmod(path, PRIVATE_SOCKET_PERMISSIONS) == 0) { "Could not protect GPG IPC socket" } // 0600
            check(listen(listener, SOCKET_BACKLOG) == 0) { "Could not listen on GPG IPC socket" }
            identity = requireNotNull(boundIdentity)
            fd = listener
        } catch (e: Throwable) {
            close(listener)
            if (boundIdentity != null && fileIdentity(path) == boundIdentity) unlink(path)
            token.fill(0)
            throw e
        }
    }

    suspend fun serve() = coroutineScope {
        val connections = Semaphore(8)
        while (true) {
            ensureActive()
            val client = accept(fd, null, null)
            if (client < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                    delay(POLL_DELAY_MS)
                    continue
                }
                error("GPG IPC listener failed")
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
                    config.log("GPG IPC connection closed")
                } finally {
                    close(client)
                    connections.release()
                }
            }
        }
    }

    private suspend fun handle(client: Int) {
        val session = GpgAgentPacketSession(config.processor, token)
        try {
            while (true) {
                val packet = withTimeoutOrNull(IO_TIMEOUT_MS) { readPacket(client) } ?: return
                val reply = try { session.process(packet) } finally { packet.fill(0) }
                try {
                    if (withTimeoutOrNull(
                        IO_TIMEOUT_MS,
                    ) { writePacket(
                        client,
                        reply.packet,
                    ) } != true || reply.close) return
                } finally {
                    reply.packet.fill(0)
                }
            }
        } finally {
            session.close()
        }
    }

    /** Call only after serve and all its children have completed. */
    fun close() {
        close(fd)
        token.fill(0)
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
        "The App Group path is too long for a GPG agent socket"
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
private fun nonblocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL)
    check(flags >= 0 && fcntl(fd, F_SETFL, flags or O_NONBLOCK) == 0 && fcntl(fd, F_SETFD, FD_CLOEXEC) == 0) {
        "Could not configure GPG transport"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun noSigpipe(fd: Int) = memScoped {
    val enabled = alloc<IntVar> { value = 1 }
    check(setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert()) == 0)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun readFully(fd: Int, size: Int): ByteArray? {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        currentCoroutineContext().ensureActive()
        val count = bytes.usePinned { read(fd, it.addressOf(offset), (size - offset).convert()) }
        when {
            count > 0 -> offset += count.toInt()
            count == 0L -> return null
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> delay(POLL_DELAY_MS)
            else -> return null
        }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun writeFully(fd: Int, bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
        currentCoroutineContext().ensureActive()
        val count = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
        when {
            count > 0 -> offset += count.toInt()
            count == 0L -> return false
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> delay(POLL_DELAY_MS)
            else -> return false
        }
    }
    return true
}

internal suspend fun readPacket(fd: Int): ByteArray? {
    val header = readFully(fd, 4) ?: return null
    val size = header.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
    if (size !in 1..GpgAgentPacketSession.MAX_PACKET_SIZE) return null
    return readFully(fd, size)
}

private suspend fun writePacket(fd: Int, packet: ByteArray): Boolean {
    if (packet.size !in 1..GpgAgentPacketSession.MAX_PACKET_SIZE) return false
    val header = ByteArray(4) { index -> (packet.size ushr (24 - index * 8)).toByte() }
    return writeFully(fd, header) && writeFully(fd, packet)
}

private suspend fun awaitReadiness(fd: Int) {
    val expected = "KEYGUARD_AGENT_READY 1\n".encodeToByteArray()
    val received = readFully(fd, expected.size)
    check(received?.contentEquals(expected) == true) { "GPG agent did not report readiness" }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun drain(fd: Int) {
    val buffer = ByteArray(4096)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        when {
            count > 0 -> buffer.fill(0)
            count == 0L -> return
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> delay(POLL_DELAY_MS)
            else -> return
        }
    }
}

private fun closeHandle(handle: NSFileHandle) { runCatching { handle.closeFile() } }

private data class FileIdentity(val device: Long, val inode: ULong)

@OptIn(ExperimentalForeignApi::class)
private fun fileIdentity(path: String): FileIdentity? = memScoped {
    val info = alloc<stat>()
    if (lstat(path, info.ptr) != 0) return@memScoped null
    FileIdentity(info.st_dev.toLong(), info.st_ino.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun ensurePrivateDirectory(path: String) {
    require(path.startsWith('/') && '\u0000' !in path)
    if (mkdir(path, PRIVATE_DIRECTORY_PERMISSIONS) != 0 && errno != EEXIST) {
        error("Could not create private GPG directory")
    }
    memScoped {
        val info = alloc<stat>()
        check(
            lstat(path, info.ptr) == 0 &&
                info.st_mode.toInt() and S_IFMT == S_IFDIR &&
                info.st_uid == geteuid() &&
                info.st_mode.toInt() and UNIX_PERMISSION_MASK == PRIVATE_DIRECTORY_PERMISSION_BITS,
        ) {
            "GPG directory must be owned by the current user with mode 0700 and must not be a symlink"
        }
    }
}

private class PrivateIpcDirectory(val path: String, val identity: FileIdentity) {
    @OptIn(ExperimentalForeignApi::class)
    fun removeIfOwned() { if (fileIdentity(path) == identity) rmdir(path) }
}

@OptIn(ExperimentalForeignApi::class)
private fun createPrivateIpcDirectory(parent: String): PrivateIpcDirectory {
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
        check(errno == EEXIST) { "Could not create private GPG IPC directory" }
    }
    error("Could not allocate private GPG IPC directory")
}

private const val SHUTDOWN_TIMEOUT_MS = 2_000L

private const val PRIVATE_SOCKET_PERMISSIONS: UShort = 0x180u

private const val SOCKET_BACKLOG = 8

private const val LOCAL_PEER_EFFECTIVE_PID = 3

private const val MAX_UNIX_SOCKET_PATH_BYTES = 103

private const val SOCKET_ADDRESS_OVERHEAD = 3

private const val PRIVATE_DIRECTORY_PERMISSIONS: UShort = 0x1c0u

private const val PRIVATE_DIRECTORY_PERMISSION_BITS = 0x1c0

private const val UNIX_PERMISSION_MASK = 0x1ff

private const val DIRECTORY_CREATE_ATTEMPTS = 10
