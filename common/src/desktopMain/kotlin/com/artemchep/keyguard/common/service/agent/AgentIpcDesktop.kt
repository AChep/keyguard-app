package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_agent_ipc_endpoint
import com.artemchep.keyguard.res.error_agent_ipc_unavailable
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcEvent
import com.artemchep.keyguard.util.agentipc.AgentIpcException
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcFailureKind
import com.artemchep.keyguard.util.agentipc.AgentIpcHost
import com.artemchep.keyguard.util.agentipc.AgentIpcListener
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import org.jetbrains.compose.resources.StringResource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where an agent's private endpoint is created: a pipe on Windows, else a new
 * private directory under a short temporary root. The JVM temporary directory
 * of macOS, under `/var/folders/...`, takes most of a socket path's budget.
 */
internal fun agentIpcLocation(prefix: String): AgentIpcLocation =
    if (CurrentPlatform is Platform.Desktop.Windows) {
        AgentIpcLocation.WindowsPipe(prefix = "$prefix-")
    } else {
        val shortRoot = Path.of("/tmp")
        val parent = if (Files.isDirectory(shortRoot)) {
            shortRoot.toString()
        } else {
            System.getProperty("java.io.tmpdir")
        }
        AgentIpcLocation.UnixDirectory(parent = parent, prefix = "$prefix-")
    }

/** The bytes of this connection. */
fun AgentIpcConnection.asAgentByteStream() = AgentByteStream(
    read = this::read,
    write = this::write,
    awaitInputOrDisconnect = this::awaitInputOrDisconnect,
)

/**
 * Logs [event] under [tag] as its fixed text, at its severity. A failed
 * session is logged by the class of its error: the message of a codec error
 * can quote the helper's bytes.
 */
internal fun LogRepository.post(
    tag: String,
    event: AgentIpcEvent,
) {
    val level = when (event.severity) {
        AgentIpcEvent.Severity.INFO -> LogLevel.INFO
        AgentIpcEvent.Severity.ERROR -> LogLevel.ERROR
    }
    post(tag, event.message, level)
}

/**
 * An agent's private endpoint that failed to start, as a localized error;
 * any other error as it is.
 */
fun Throwable.asAgentIpcAppException(): Throwable = when ((this as? AgentIpcException)?.kind) {
    null -> this
    AgentIpcFailureKind.UNAVAILABLE -> AgentIpcStartException(Res.string.error_agent_ipc_unavailable, this)
    else -> AgentIpcStartException(Res.string.error_agent_ipc_endpoint, this)
}

private class AgentIpcStartException(
    title: StringResource,
    cause: Throwable,
) : RuntimeException("Agent IPC failed to start", cause), Readable {
    override val title = TextHolder.Res(title)
}

/**
 * The private endpoint of one agent server: [bind] creates it, [serve] accepts
 * the helper's connections, and [close] stops both. Events are logged under
 * [tag].
 */
internal class AgentIpcServerHost(
    private val logRepository: LogRepository,
    private val tag: String,
    private val maxConnections: Int,
) {
    private val lock = Any()

    // Guarded by the lock.
    private var host: AgentIpcHost? = null
    private var closed = false

    /**
     * Creates the endpoint at [location], which exists once this returns, and
     * returns it: the value of a helper's `--ipc-socket`. Binds once.
     */
    fun bind(location: AgentIpcLocation): String {
        val listener = AgentIpcListener.bind(location)
        val host = AgentIpcHost(listener, maxConnections) { event ->
            logRepository.post(tag, event)
        }
        val bound = synchronized(lock) {
            if (closed || this.host != null) {
                false
            } else {
                this.host = host
                true
            }
        }
        if (!bound) {
            host.stop()
            error("The IPC server is closed or already bound")
        }
        return host.endpoint
    }

    /**
     * Serves each connection of the [expectedPeer] with [handler] until [close].
     * Accepts nothing before [expectedPeer] returns.
     */
    suspend fun serve(
        expectedPeer: suspend () -> AgentIpcExpectedPeer,
        handler: suspend (AgentIpcConnection) -> Unit,
    ) {
        val host = synchronized(lock) {
            checkNotNull(host) { "The IPC server is not bound" }
        }
        host.serve(expectedPeer, handler)
    }

    /**
     * Closes the endpoint and every connection, and releases a [serve] that waits
     * for its peer. Idempotent, non-blocking and safe from any thread.
     */
    fun close() {
        val host = synchronized(lock) {
            closed = true
            host
        }
        host?.stop()
    }
}
