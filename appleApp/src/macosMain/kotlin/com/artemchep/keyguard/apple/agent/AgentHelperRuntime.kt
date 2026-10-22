package com.artemchep.keyguard.apple.agent

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
    suspend fun serve()

    /** Call only after serve and all its children have completed. */
    fun close()
}

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
 * it over a private IPC socket. [dataDirectoryName] names an optional private
 * subdirectory; it and the [publicSocket] path are passed to the helper through
 * [arguments], which follow the IPC socket and parent PID. */
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
     * of [authToken] that is wiped when its lifetime ends. */
    @Suppress("TooGenericExceptionCaught") // Own the helper lifetime even when launch or readiness fails.
    suspend fun start(
        scope: CoroutineScope,
        authToken: ByteArray,
        server: (ipcSocket: String, token: ByteArray, childPid: () -> Int?) -> AgentHelperIpcServer,
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
                if (!ready.completeExceptionally(e)) {
                    onFailure(e)
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
        createServer: (ipcSocket: String, token: ByteArray, childPid: () -> Int?) -> AgentHelperIpcServer,
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
        val ipcDirectory = createPrivateIpcDirectory(directory)
        val ipcSocket = ipcDirectory.path + "/i"
        val helper = AgentHelperProcess(
            name,
            binary,
            listOf(
                "--ipc-socket", ipcSocket,
                "--parent-pid", getpid().toString(),
            ) + arguments(publicSocket, dataDirectory),
        )
        var server: AgentHelperIpcServer? = null
        var serverJob: Job? = null
        try {
            // Binding is synchronous and complete before the child can connect.
            server = createServer(ipcSocket, token, helper::pid)
            serverJob = launch { server.serve() }
            helper.launch(this, token)
            val lifetime = currentCoroutineContext().job
            ready.complete(AgentHelperHandle(lifetime, helper, publicSocket))
            onReady()
            error("$name exited with status ${helper.awaitExit()}")
        } finally {
            withContext(NonCancellable) {
                serverJob?.cancelAndJoin()
                server?.close()
                helper.stop()
                ipcDirectory.removeIfOwned()
            }
        }
    }
}
