package com.artemchep.keyguard.desktop.nativebundle

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.sshagent.SshAgentManager
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.createDesktopKoinApplication
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcListener
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val AGENT_IPC_TIMEOUT_MS = 5_000L
private const val OBSERVER_PENDING_MS = 100L
private const val HELPER_STOP_TIMEOUT_SECONDS = 5L
private const val SMOKE_PREFIX = "keyguard-smoke-"
private const val SMOKE_PIPE_NAME_BYTES = 16

private val REQUEST = "ping".encodeToByteArray()
private val RESPONSE = "pong".encodeToByteArray()

private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * The bundled agent IPC library, in this process: a JDK client of a new endpoint is
 * reported as this process and round-trips four bytes, the disconnect observer
 * returns once the client closes, and closing the listener removes the endpoint.
 */
internal fun verifyPackagedAgentIpc() = withShortSmokeDirectory { directory ->
    val location = if (isWindows) {
        AgentIpcLocation.WindowsPipe(prefix = SMOKE_PREFIX)
    } else {
        AgentIpcLocation.UnixDirectory(parent = directory.toString())
    }
    val endpoint = runBlocking {
        AgentIpcListener.bind(location).use { listener ->
            // One attempt: the endpoint is connectable once bind returns.
            SmokeClient.connect(listener.endpoint).use { client ->
                withTimeout(AGENT_IPC_TIMEOUT_MS) { listener.accept() }.use { connection ->
                    verifyConnection(connection, client)
                }
            }
            listener.endpoint
        }
    }
    check(SmokeClient.isRemoved(endpoint)) { "Closing the listener left its endpoint" }
}

private suspend fun verifyConnection(
    connection: AgentIpcConnection,
    client: SmokeClient,
) = coroutineScope {
    check(connection.peerPid == ProcessHandle.current().pid()) { "The peer is not this process" }
    client.write(REQUEST)
    val request = withTimeout(AGENT_IPC_TIMEOUT_MS) { connection.readFully(REQUEST.size) }
    check(request.contentEquals(REQUEST)) { "The server read other bytes" }
    withTimeout(AGENT_IPC_TIMEOUT_MS) { connection.write(RESPONSE) }
    check(client.readFully(RESPONSE.size).contentEquals(RESPONSE)) { "The client read other bytes" }

    val observer = async { connection.awaitInputOrDisconnect() }
    delay(OBSERVER_PENDING_MS)
    check(!observer.isCompleted) { "The observer returned while the client was connected" }
    client.close()
    withTimeout(AGENT_IPC_TIMEOUT_MS) { observer.await() }
}

private suspend fun AgentIpcConnection.readFully(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(buffer, offset, size - offset)
        check(count > 0) { "The client closed early" }
        offset += count
    }
    return buffer
}

/**
 * The real cross-process path: the [SshAgentManager] of the desktop Koin graph starts
 * the bundled helper. Start returns only once the helper connected to the private
 * endpoint, passed the peer check and authenticated, before it reports ready. Its
 * public socket is private to this probe.
 */
internal fun verifyPackagedSshHelperIpc() = withShortSmokeDirectory { directory ->
    val application = createDesktopKoinApplication()
    try {
        val koin = application.koin
        // A locked vault: the agent's session watcher must not restore the
        // user's persisted session.
        koin.get<SessionReadWriteRepository>().put(MasterSession.Empty())
        val manager = koin.get<SshAgentManager>()
        val socket = if (isWindows) {
            val name = ByteArray(SMOKE_PIPE_NAME_BYTES).also { SecureRandom().nextBytes(it) }
            Path.of("\\\\.\\pipe\\$SMOKE_PREFIX${HexFormat.of().formatHex(name)}")
        } else {
            directory.resolve("ssh-agent.sock")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                // A failed start stops what it started.
                val process = manager.start(scope = scope, agentSocketPath = socket)
                manager.stop()
                check(process.waitFor(HELPER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "The packaged SSH helper did not stop"
                }
            }
        } finally {
            scope.cancel()
        }
    } finally {
        application.close()
    }
}

/**
 * Runs [block] with a new private directory under a short root: the JVM temporary
 * directory of macOS, under `/var/folders/...`, takes most of a socket path's budget.
 */
private inline fun <T> withShortSmokeDirectory(block: (Path) -> T): T {
    val shortRoot = Path.of("/tmp")
    val directory = if (!isWindows && Files.isDirectory(shortRoot)) {
        Files.createTempDirectory(shortRoot, SMOKE_PREFIX)
    } else {
        Files.createTempDirectory(SMOKE_PREFIX)
    }.toRealPath()
    try {
        return block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}

/** A blocking JDK client, as a helper connects: a Unix socket, or a synchronous pipe handle. */
private sealed interface SmokeClient : Closeable {
    fun write(bytes: ByteArray)

    /** Reads at most [length] bytes into [buffer] at [offset]; -1 at the end of the stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun readFully(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(buffer, offset, size - offset)
            check(count > 0) { "The server closed early" }
            offset += count
        }
        return buffer
    }

    class Socket(
        private val channel: SocketChannel,
    ) : SmokeClient {
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            channel.read(ByteBuffer.wrap(buffer, offset, length))

        override fun close() = channel.close()
    }

    class Pipe(
        private val file: RandomAccessFile,
    ) : SmokeClient {
        override fun write(bytes: ByteArray) = file.write(bytes)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            file.read(buffer, offset, length)

        override fun close() = file.close()
    }

    companion object {
        fun connect(endpoint: String): SmokeClient = if (isWindows) {
            Pipe(RandomAccessFile(endpoint, "rw"))
        } else {
            Socket(SocketChannel.open(UnixDomainSocketAddress.of(endpoint)))
        }

        /** Whether [endpoint] is gone: the pipe, or the socket's run directory. */
        fun isRemoved(endpoint: String): Boolean = if (isWindows) {
            try {
                RandomAccessFile(endpoint, "rw").close()
                false
            } catch (_: FileNotFoundException) {
                true
            }
        } else {
            Files.notExists(Path.of(endpoint).parent, LinkOption.NOFOLLOW_LINKS)
        }
    }
}
