package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubConnectionEvent
import com.artemchep.keyguard.util.signalr.HubConnectionState
import com.artemchep.keyguard.util.signalr.logger.Logger
import com.artemchep.keyguard.util.signalr.internal.util.EstablishedConnection
import com.artemchep.keyguard.util.signalr.internal.util.connect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

private const val COMMAND_BUFFER_CAPACITY = 64

internal suspend fun runHubConnectionController(
    scope: CoroutineScope,
    events: SendChannel<HubConnectionEvent>,
    options: HubConnectionOptions,
) {
    val commands = Channel<HubConnectionCommand>(COMMAND_BUFFER_CAPACITY)
    var lifecycle: Lifecycle = Lifecycle.Disconnected
    var nextSessionId = 1L
    var disposed = false

    fun isCurrentConnectAttempt(
        sessionId: Long,
    ): Boolean = lifecycle.isCurrentConnectAttempt(sessionId)

    fun isCurrentConnectedSession(
        sessionId: Long,
    ): Boolean = lifecycle.isCurrentConnectedSession(sessionId)

    suspend fun emitEvent(
        event: HubConnectionEvent,
    ) {
        if (!scope.isActive) {
            return
        }

        try {
            events.send(event)
        } catch (_: ClosedSendChannelException) {
            // The downstream collector has already stopped.
        } catch (ex: CancellationException) {
            if (scope.isActive) {
                throw ex
            }
        }
    }

    suspend fun stopInternal(
        reason: HubConnectionCloseReason,
    ) {
        val previous = lifecycle
        if (previous == Lifecycle.Disconnected) {
            return
        }

        lifecycle = Lifecycle.Disconnecting

        val previousConnectionId = previous.connectionIdOrNull()
        try {
            emitEvent(
                HubConnectionEvent.StateChanged(
                    state = HubConnectionState.DISCONNECTING,
                    connectionId = previousConnectionId,
                    reason = reason,
                ),
            )
        } finally {
            options.logger.log(
                Logger.Severity.INFO,
                "[${options.baseUrl}] ${reason.logMessage()}",
                null,
            )

            runCatching {
                previous.cancelAndJoin(
                    closeTimeout = options.closeTimeout,
                )
            }

            lifecycle = Lifecycle.Disconnected
            emitEvent(
                HubConnectionEvent.StateChanged(
                    state = HubConnectionState.DISCONNECTED,
                    reason = reason,
                ),
            )
        }
    }

    fun launchConnect(): ConnectAttempt {
        val sessionId = nextSessionId++
        val connectJob = scope.launch(start = CoroutineStart.LAZY) {
            var connection: EstablishedConnection? = null
            try {
                connection = connect(options)
                val delivered = commands.sendCommand(
                    HubConnectionCommand.ConnectSucceeded(
                        sessionId = sessionId,
                        connection = connection,
                    ),
                )
                if (!delivered) {
                    connection.transport.stop()
                }
                connection = null
            } catch (ex: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        connection?.transport?.stop()
                    }
                }
                throw ex
            } catch (ex: Throwable) {
                commands.sendCommand(
                    HubConnectionCommand.ConnectFailed(
                        sessionId = sessionId,
                        cause = ex,
                    ),
                )
            }
        }

        return ConnectAttempt(
            sessionId = sessionId,
            job = connectJob,
        )
    }

    suspend fun startInternal() {
        if (disposed) {
            throw IllegalStateException("HubConnection has already stopped.")
        }

        val attempt = launchConnect()
        lifecycle = Lifecycle.Connecting(
            sessionId = attempt.sessionId,
            connectJob = attempt.job,
        )
        emitEvent(
            HubConnectionEvent.StateChanged(
                state = HubConnectionState.CONNECTING,
            ),
        )
        attempt.job.start()
    }

    suspend fun handleConnectSucceeded(
        command: HubConnectionCommand.ConnectSucceeded,
    ) {
        val connection = command.connection
        if (!isCurrentConnectAttempt(command.sessionId)) {
            connection.transport.stop()
            return
        }

        val sessionJob = scope.launchHubSession(
            id = command.sessionId,
            transport = connection.transport,
            protocol = options.protocol,
            keepAliveInterval = options.keepAliveInterval,
            serverTimeout = options.serverTimeout,
            logger = options.logger,
            initialPayload = connection.initialPayload,
            events = commands,
            start = CoroutineStart.LAZY,
        )
        lifecycle = Lifecycle.Connected(
            sessionId = command.sessionId,
            transport = connection.transport,
            connectionId = connection.connectionId,
            sessionJob = sessionJob,
        )
        emitEvent(
            HubConnectionEvent.StateChanged(
                state = HubConnectionState.CONNECTED,
                connectionId = connection.connectionId,
            ),
        )
        sessionJob.start()
    }

    suspend fun handleConnectFailed(
        command: HubConnectionCommand.ConnectFailed,
    ): CommandLoopResult {
        if (!isCurrentConnectAttempt(command.sessionId)) {
            return CommandLoopResult.Continue
        }

        stopInternal(
            reason = HubConnectionCloseReason.Failed(command.cause),
        )
        return CommandLoopResult.Stop
    }

    suspend fun handleSessionClosed(
        command: HubConnectionCommand.SessionClosed,
    ): CommandLoopResult {
        if (!isCurrentConnectedSession(command.sessionId)) {
            return CommandLoopResult.Continue
        }

        stopInternal(command.reason)
        return CommandLoopResult.Stop
    }

    suspend fun handleInvocationReceived(
        command: HubConnectionCommand.InvocationReceived,
    ) {
        if (!isCurrentConnectedSession(command.sessionId)) {
            return
        }

        emitEvent(
            HubConnectionEvent.InvocationReceived(command.invocation),
        )
    }

    suspend fun handle(
        command: HubConnectionCommand,
    ): CommandLoopResult = when (command) {
        HubConnectionCommand.Start -> {
            startInternal()
            CommandLoopResult.Continue
        }

        is HubConnectionCommand.ConnectSucceeded -> {
            handleConnectSucceeded(command)
            CommandLoopResult.Continue
        }

        is HubConnectionCommand.ConnectFailed -> {
            handleConnectFailed(command)
        }

        is HubConnectionCommand.SessionClosed -> {
            handleSessionClosed(command)
        }

        is HubConnectionCommand.InvocationReceived -> {
            handleInvocationReceived(command)
            CommandLoopResult.Continue
        }
    }

    suspend fun commandLoop() {
        while (true) {
            val command = commands
                .receiveCatching()
                .getOrNull()
                ?: return
            val result = try {
                handle(command)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                options.logger.log(
                    Logger.Severity.ERROR,
                    "[${options.baseUrl}] Hub connection controller error",
                    ex,
                )
                stopInternal(HubConnectionCloseReason.Failed(ex))
                CommandLoopResult.Stop
            }
            if (result == CommandLoopResult.Stop) {
                return
            }
        }
    }

    fun disposeController() {
        if (disposed) {
            return
        }

        disposed = true
        commands.close()
    }

    try {
        commands.send(HubConnectionCommand.Start)
        commandLoop()
    } finally {
        withContext(NonCancellable) {
            stopInternal(
                reason = HubConnectionCloseReason.ClientStopped,
            )
            disposeController()
        }
    }
}

private data class ConnectAttempt(
    val sessionId: Long,
    val job: Job,
)

private enum class CommandLoopResult {
    Continue,
    Stop,
}

private sealed interface Lifecycle {
    data object Disconnected : Lifecycle
    data object Disconnecting : Lifecycle

    data class Connecting(
        val sessionId: Long,
        val connectJob: Job,
    ) : Lifecycle

    data class Connected(
        val sessionId: Long,
        val transport: Transport,
        val connectionId: String?,
        val sessionJob: Job,
    ) : Lifecycle
}

private fun Lifecycle.connectionIdOrNull(): String? =
    (this as? Lifecycle.Connected)
        ?.connectionId

private fun Lifecycle.isCurrentConnectAttempt(
    sessionId: Long,
): Boolean = when (this) {
    is Lifecycle.Connecting -> this.sessionId == sessionId

    // not connecting
    Lifecycle.Disconnected,
    Lifecycle.Disconnecting,
    is Lifecycle.Connected,
        -> false
}

private fun Lifecycle.isCurrentConnectedSession(
    sessionId: Long,
): Boolean = when (this) {
    is Lifecycle.Connected -> this.sessionId == sessionId

    // not connected
    Lifecycle.Disconnected,
    Lifecycle.Disconnecting,
    is Lifecycle.Connecting,
        -> false
}

private suspend fun Lifecycle.cancelAndJoin(
    closeTimeout: Duration,
) = when (this) {
    Lifecycle.Disconnected,
    Lifecycle.Disconnecting,
        -> Unit

    is Lifecycle.Connecting -> withTimeoutOrNull(closeTimeout) {
        connectJob.cancelAndJoin()
    }

    is Lifecycle.Connected -> withTimeoutOrNull(closeTimeout) {
        sessionJob.cancel()
        try {
            transport.stop()
        } finally {
            sessionJob.join()
        }
    }
}

private suspend fun SendChannel<HubConnectionCommand>.sendCommand(
    command: HubConnectionCommand,
): Boolean = try {
    send(command)
    true
} catch (_: ClosedSendChannelException) {
    false
}

private fun HubConnectionCloseReason.logMessage(): String = when (this) {
    HubConnectionCloseReason.ClientStopped -> "Stopping connection"
    HubConnectionCloseReason.TransportClosed -> "Connection transport closed."
    is HubConnectionCloseReason.ServerClosed -> error
        ?: "Connection closed by server."

    is HubConnectionCloseReason.Failed -> cause.message
        ?: "Connection failed."
}
