package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Shared IPC server that listens for connections from an agent binary
 * over a Unix domain socket (macOS/Linux) or named pipe (Windows).
 *
 * The transport lifecycle (bind, permissions, accept loop, connection
 * concurrency limiting and teardown) is common to all agents; the
 * per-connection protocol handling is delegated to [session].
 */
internal class AgentIpcServer(
    private val logRepository: LogRepository,
    private val scope: CoroutineScope,
    private val tag: String,
    private val maxConcurrentConnections: Int = 8,
    private val peerVerificationPolicy: AgentIpcPeerVerificationPolicy,
    private val session: suspend (AgentPacketChannel) -> Unit,
) {
    private val serverChannelLock = Any()
    private var serverChannelRef: ServerSocketChannel? = null
    private var windowsPipeServerRef: WindowsNamedPipeServer? = null

    fun stop() {
        val resources = synchronized(serverChannelLock) {
            serverChannelRef to windowsPipeServerRef
        }
        try {
            resources.first?.close()
        } catch (_: Exception) {
        }
        try {
            resources.second?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the IPC server on the given Unix domain socket path.
     *
     * This method blocks (suspends) until the server is stopped or an error occurs.
     *
     * @param socketPath The path to the Unix domain socket.
     * @param onReady An optional [CompletableDeferred] that will be completed once the server
     *   has bound the socket and is ready to accept connections. This allows callers to
     *   reliably wait for the server to be ready before spawning the agent binary.
     */
    suspend fun start(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) = start(
        endpoint = AgentIpcEndpoint.UnixSocket(
            socketPath = socketPath,
            directory = socketPath.parent ?: socketPath,
        ),
        onReady = onReady,
    )

    suspend fun start(
        endpoint: AgentIpcEndpoint,
        onReady: CompletableDeferred<Unit>? = null,
    ) = when (endpoint) {
        is AgentIpcEndpoint.UnixSocket -> startUnix(endpoint.socketPath, onReady)
        is AgentIpcEndpoint.WindowsPipe -> startWindows(endpoint.pipeName, onReady)
    }

    private suspend fun startUnix(
        socketPath: Path,
        onReady: CompletableDeferred<Unit>? = null,
    ) {
        // Clean up stale socket file.
        Files.deleteIfExists(socketPath)

        // Ensure parent directory exists.
        socketPath.parent?.let { Files.createDirectories(it) }

        val address = UnixDomainSocketAddress.of(socketPath)
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        synchronized(serverChannelLock) {
            serverChannelRef = serverChannel
        }
        serverChannel.bind(address)
        val cancellationHandler = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { stop() }

        // Restrict socket permissions to owner-only (0600) to prevent
        // other local users from connecting to the IPC socket.
        try {
            Files.setPosixFilePermissions(
                socketPath,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — skip.
            // Windows does not use Unix domain socket file permissions
            // for access control.
        }

        // Signal that the server is ready to accept connections.
        onReady?.complete(Unit)

        val connectionSemaphore = Semaphore(maxConcurrentConnections)

        try {
            // AgentManager starts the listener before spawning the helper. Do not
            // accept any queued connection until the exact child Process handle is
            // available for kernel-level peer verification.
            val peerVerification = peerVerificationPolicy.resolve(logRepository, tag)
            logRepository.post(tag, "IPC server listening on $socketPath", LogLevel.INFO)

            while (scope.isActive && currentCoroutineContext().isActive) {
                val clientChannel = try {
                    withContext(Dispatchers.IO) {
                        serverChannel.accept()
                    }
                } catch (_: AsynchronousCloseException) {
                    break
                } catch (_: ClosedChannelException) {
                    break
                }
                if (!connectionSemaphore.tryAcquire()) {
                    val errorMessage = "Connection rejected: too many concurrent IPC connections " +
                            "(limit=$maxConcurrentConnections)"
                    logRepository.post(tag, errorMessage, LogLevel.ERROR)

                    try {
                        clientChannel.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                // Handle each connection in a separate coroutine.
                scope.launch(Dispatchers.IO) {
                    try {
                        handleConnection(
                            channel = clientChannel,
                            peerVerification = peerVerification,
                        )
                    } finally {
                        connectionSemaphore.release()
                    }
                }
            }
        } finally {
            cancellationHandler?.dispose()
            stop()
            Files.deleteIfExists(socketPath)
            synchronized(serverChannelLock) {
                serverChannelRef = null
            }
        }
    }

    private suspend fun startWindows(
        pipeName: String,
        onReady: CompletableDeferred<Unit>? = null,
    ) {
        val pipeServer = WindowsNamedPipeServer(pipeName)
        synchronized(serverChannelLock) {
            windowsPipeServerRef = pipeServer
        }
        val cancellationHandler = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { stop() }

        val connectionSemaphore = Semaphore(maxConcurrentConnections)

        try {
            // Create the first owner-only pipe instance before publishing
            // readiness. Otherwise the helper's one-shot open can race the
            // first accept() call and observe a nonexistent pipe.
            pipeServer.prepare()
            onReady?.complete(Unit)

            // See startUnix: keep the accept loop gated until AgentManager has
            // published the exact child Process handle.
            val peerVerification = peerVerificationPolicy.resolve(logRepository, tag)
            logRepository.post(tag, "IPC server listening on $pipeName", LogLevel.INFO)

            while (scope.isActive && currentCoroutineContext().isActive) {
                val connection = try {
                    withContext(Dispatchers.IO) {
                        pipeServer.accept()
                    }
                } catch (_: ClosedChannelException) {
                    break
                } catch (e: IOException) {
                    if (!currentCoroutineContext().isActive) {
                        break
                    }
                    throw e
                }
                if (!connectionSemaphore.tryAcquire()) {
                    val errorMessage = "Connection rejected: too many concurrent IPC connections " +
                            "(limit=$maxConcurrentConnections)"
                    logRepository.post(tag, errorMessage, LogLevel.ERROR)

                    try {
                        connection.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        handleConnection(
                            channel = WindowsNamedPipePacketChannel(connection),
                            close = connection::close,
                            verifyPeer = {
                                when (peerVerification) {
                                    is ResolvedAgentIpcPeerVerification.ExactProcess ->
                                        connection.verifyClient(peerVerification.process)

                                    ResolvedAgentIpcPeerVerification.TestOnlyUnverified -> Unit
                                }
                            },
                        )
                    } finally {
                        connectionSemaphore.release()
                    }
                }
            }
        } finally {
            cancellationHandler?.dispose()
            stop()
            synchronized(serverChannelLock) {
                windowsPipeServerRef = null
            }
        }
    }

    /**
     * Handles a single client connection by delegating to [session].
     */
    private suspend fun handleConnection(
        channel: SocketChannel,
        peerVerification: ResolvedAgentIpcPeerVerification,
    ) = handleConnection(
        channel = AgentIpcProtocol.open(channel),
        close = channel::close,
        verifyPeer = {
            when (peerVerification) {
                is ResolvedAgentIpcPeerVerification.ExactProcess ->
                    verifyUnixAgentPeer(channel, peerVerification.process)

                ResolvedAgentIpcPeerVerification.TestOnlyUnverified -> Unit
            }
        },
    )

    private suspend fun handleConnection(
        channel: AgentPacketChannel,
        close: () -> Unit,
        verifyPeer: () -> Unit,
    ) {
        try {
            verifyPeer()
            session(channel)
        } catch (_: AsynchronousCloseException) {
            // Normal during server shutdown.
        } catch (_: ClosedChannelException) {
            // Normal during server shutdown.
        } catch (_: EOFException) {
            logRepository.post(tag, "Client disconnected", LogLevel.INFO)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                val errorMessage = "Error handling IPC connection: ${e.message}"
                logRepository.post(tag, errorMessage, LogLevel.ERROR)
            }
        } finally {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Marks the only API that permits IPC sessions without kernel-authenticated
 * exact-process verification. It exists solely for in-process component tests
 * whose client is the test JVM rather than a spawned agent helper.
 */
@RequiresOptIn(
    message = "Unverified agent IPC is restricted to in-process component tests.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
internal annotation class TestOnlyUnverifiedAgentIpcApi

@TestOnlyUnverifiedAgentIpcApi
internal data object TestOnlyUnverifiedAgentIpcPeer

internal sealed interface AgentIpcPeerVerificationPolicy {
    data class ExactProcess(
        val expectedProcess: Deferred<Process>,
    ) : AgentIpcPeerVerificationPolicy

    @TestOnlyUnverifiedAgentIpcApi
    data object TestOnlyUnverified : AgentIpcPeerVerificationPolicy
}

private sealed interface ResolvedAgentIpcPeerVerification {
    data class ExactProcess(
        val process: Process,
    ) : ResolvedAgentIpcPeerVerification

    data object TestOnlyUnverified : ResolvedAgentIpcPeerVerification
}

@OptIn(TestOnlyUnverifiedAgentIpcApi::class)
private suspend fun AgentIpcPeerVerificationPolicy.resolve(
    logRepository: LogRepository,
    tag: String,
): ResolvedAgentIpcPeerVerification = when (this) {
    is AgentIpcPeerVerificationPolicy.ExactProcess ->
        ResolvedAgentIpcPeerVerification.ExactProcess(expectedProcess.await())

    AgentIpcPeerVerificationPolicy.TestOnlyUnverified -> {
        logRepository.post(
            tag,
            "IPC peer verification is DISABLED by an in-process test-only policy",
            LogLevel.WARNING,
        )
        ResolvedAgentIpcPeerVerification.TestOnlyUnverified
    }
}
