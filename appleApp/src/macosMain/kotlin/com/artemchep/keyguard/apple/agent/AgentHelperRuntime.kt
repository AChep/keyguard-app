package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.util.agentipc.AgentIpcException
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.getpid

/** The private IPC server of a helper started by [AgentHelperRuntime]. */
internal interface AgentHelperIpcServer {
    /** The helper's `--ipc-socket`. The endpoint exists once the server is created. */
    val endpoint: String

    /**
     * Serves the helper's connections until [close]. Accepts nothing before
     * [expectedPeer] returns, and only connections of that process. Throws
     * [AgentIpcException] when the endpoint fails.
     */
    suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer)

    /** Closes the endpoint and every connection, and erases the token. Idempotent. */
    fun close()
}

/**
 * A helper's private endpoint failed. It has no message, so the controllers
 * show their localized start failure; the [cause] names the failure kind.
 */
internal class AgentHelperIpcException(
    cause: AgentIpcException,
) : Exception(null, cause)

/** A ready helper. Its lifetime ends when the helper exits or on [stop]. */
internal class AgentHelperHandle(
    private val lifetime: Job,
    private val helper: AgentHelperProcess,
    /** The socket the helper serves to external clients. */
    val publicSocket: String,
) {
    val isRunning: Boolean get() = lifetime.isActive && helper.isRunning

    /** Suspends until the helper has stopped. */
    suspend fun join() = lifetime.join()

    fun stop() { lifetime.cancel() }
}

/** Runs a bundled helper from the App Group directory [directoryName] and serves
 * it over a private IPC endpoint, created in a new run directory there.
 * [dataDirectoryName] names an optional private subdirectory; it and the
 * [publicSocket] path are passed to the helper through [arguments], which follow
 * the IPC endpoint and parent PID. */
@OptIn(ExperimentalForeignApi::class)
internal class AgentHelperRuntime(
    private val name: String,
    private val binaryPath: () -> String?,
    private val groupPath: () -> String?,
    private val directoryName: String,
    private val dataDirectoryName: String? = null,
    private val publicSocket: (group: String, directory: String) -> String = { _, directory -> "$directory/s" },
    private val arguments: (publicSocket: String, dataDirectory: String?) -> List<String>,
) {
    /** Returns once the helper is ready; a failure before that is thrown, a later
     * one is passed to [onFailure]. The helper lives in [scope] and receives a copy
     * of [authToken] that is wiped when its lifetime ends. [server] creates the
     * private endpoint under the given parent directory. */
    @Suppress("TooGenericExceptionCaught") // Own the helper lifetime even when launch or readiness fails.
    suspend fun start(
        scope: CoroutineScope,
        authToken: ByteArray,
        server: (parent: String, token: ByteArray) -> AgentHelperIpcServer,
        onFailure: (Exception) -> Unit,
        onReady: () -> Unit = {},
    ): AgentHelperHandle {
        val ready = CompletableDeferred<AgentHelperHandle>()
        val token = authToken.copyOf()
        val lifetime = scope.launch(Dispatchers.Default) {
            try {
                runHelper(token, server, ready, onReady)
            } catch (e: CancellationException) {
                ready.cancel(e)
                throw e
            } catch (e: Exception) {
                val failure = e.withoutIpcMessage()
                if (!ready.completeExceptionally(failure)) {
                    onFailure(failure)
                }
            } finally {
                token.fill(0)
            }
        }
        lifetime.invokeOnCompletion { cause ->
            token.fill(0)
            if (!ready.isCompleted) ready.completeExceptionally(
                cause ?: IllegalStateException("$name stopped before startup"),
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
        token: ByteArray,
        createServer: (parent: String, token: ByteArray) -> AgentHelperIpcServer,
        ready: CompletableDeferred<AgentHelperHandle>,
        onReady: () -> Unit,
    ): Unit = coroutineScope {
        val binary = requireNotNull(binaryPath()) { "Bundled $name is unavailable" }
        val group = requireNotNull(groupPath()) { "$name requires a provisioned, writable App Group" }
        val directory = group.trimEnd('/') + "/" + directoryName
        val publicSocket = publicSocket(group, directory)
        requireSocketPath(publicSocket)
        ensurePrivateDirectory(directory)
        val dataDirectory = dataDirectoryName?.let { "$directory/$it" }
        dataDirectory?.let(::ensurePrivateDirectory)
        var server: AgentHelperIpcServer? = null
        var helper: AgentHelperProcess? = null
        var serverJob: Job? = null
        try {
            // Binding is synchronous and complete before the child can connect. The
            // endpoint's run directory is created under [directory], and removed on close.
            val ipcServer = createServer(directory, token)
            server = ipcServer
            val process = AgentHelperProcess(
                name,
                binary,
                listOf(
                    "--ipc-socket", ipcServer.endpoint,
                    "--parent-pid", getpid().toString(),
                ) + arguments(publicSocket, dataDirectory),
            )
            helper = process
            // Nothing is accepted before this exact child is launched.
            val peer = CompletableDeferred<AgentIpcExpectedPeer>()
            serverJob = launch { ipcServer.serve { peer.await() } }
            process.launch(this, token) { peer.complete(process.expectedPeer) }
            val lifetime = currentCoroutineContext().job
            ready.complete(AgentHelperHandle(lifetime, process, publicSocket))
            onReady()
            error("$name exited with status ${process.awaitExit()}")
        } finally {
            withContext(NonCancellable) {
                serverJob?.cancelAndJoin()
                server?.close()
                helper?.stop()
            }
        }
    }
}

/** An [AgentIpcException] as an [AgentHelperIpcException]; any other failure as it is. */
private fun Exception.withoutIpcMessage(): Exception =
    if (this is AgentIpcException) AgentHelperIpcException(this) else this
