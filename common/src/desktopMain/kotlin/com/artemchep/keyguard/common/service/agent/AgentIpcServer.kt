package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
                        handleConnection(clientChannel)
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

        onReady?.complete(Unit)

        val connectionSemaphore = Semaphore(maxConcurrentConnections)

        try {
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
    ) = handleConnection(
        channel = AgentIpcProtocol.open(channel),
        close = channel::close,
    )

    private suspend fun handleConnection(
        channel: AgentPacketChannel,
        close: () -> Unit,
    ) {
        try {
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
