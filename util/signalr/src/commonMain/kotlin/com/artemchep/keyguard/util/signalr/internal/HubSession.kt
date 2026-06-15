package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.logger.Logger
import com.artemchep.keyguard.util.signalr.TransferFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

internal fun CoroutineScope.launchHubSession(
    id: Long,
    transport: Transport,
    protocol: HubProtocol,
    keepAliveInterval: Duration,
    serverTimeout: Duration,
    logger: Logger,
    initialPayload: ByteArray?,
    events: SendChannel<HubConnectionCommand>,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
): Job = launch(context, start) {
    supervisorScope {
        suspend fun processReceived(
            payload: ByteArray,
        ) {
            val messages = protocol.parseMessages(payload)
            messages.forEach { message ->
                when (message) {
                    is HubMessage.Close -> events.sendCommand(
                        HubConnectionCommand.SessionClosed(
                            sessionId = id,
                            reason = HubConnectionCloseReason.ServerClosed(
                                error = message.error,
                                allowReconnect = message.allowReconnect,
                            ),
                        ),
                    )

                    is HubMessage.Invocation -> events.sendCommand(
                        HubConnectionCommand.InvocationReceived(
                            sessionId = id,
                            invocation = message,
                        ),
                    )

                    is HubMessage.StreamInvocation -> Unit
                    is HubMessage.Ping -> Unit
                    is HubMessage.CancelInvocation -> Unit
                    is HubMessage.StreamItem -> Unit
                    is HubMessage.Completion -> Unit
                }
            }
        }

        suspend fun reportFailure(
            cause: Throwable,
        ) {
            if (cause is CancellationException) {
                throw cause
            }

            events.sendCommand(
                HubConnectionCommand.SessionClosed(
                    sessionId = id,
                    reason = HubConnectionCloseReason.Failed(cause),
                ),
            )
        }

        fun launchServerTimeout(): Job = launch {
            delay(serverTimeout)

            val cause =
                RuntimeException("Server timeout elapsed without receiving a message from the server.")
            reportFailure(cause)
        }

        // Keep alive
        launch {
            try {
                while (isActive) {
                    delay(keepAliveInterval)

                    val msg = HubMessage.Ping()
                    sendMessage(
                        message = msg,
                        transport = transport,
                        protocol = protocol,
                        logger = logger,
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                reportFailure(ex)
            }
        }

        launch {
            var serverTimeoutJob = launchServerTimeout()
            try {
                initialPayload?.let { payload ->
                    serverTimeoutJob.cancel()
                    processReceived(payload)
                    serverTimeoutJob = launchServerTimeout()
                }

                transport.receive()
                    .collect { payload ->
                        serverTimeoutJob.cancel()
                        processReceived(payload)
                        serverTimeoutJob = launchServerTimeout()
                    }
                events.sendCommand(
                    HubConnectionCommand.SessionClosed(
                        sessionId = id,
                        reason = HubConnectionCloseReason.TransportClosed,
                    ),
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                reportFailure(ex)
            } finally {
                serverTimeoutJob.cancel()
            }
        }

        awaitCancellation()
    }
}

private suspend fun SendChannel<HubConnectionCommand>.sendCommand(
    command: HubConnectionCommand,
) {
    try {
        send(command)
    } catch (_: ClosedSendChannelException) {
        // The controller has already reached a terminal state.
    }
}

private suspend fun sendMessage(
    message: HubMessage,
    transport: Transport,
    protocol: HubProtocol,
    logger: Logger,
) {
    val serializedMessage = protocol.writeMessage(message)
    try {
        when (protocol.transferFormat) {
            TransferFormat.Binary -> {
                transport.send(serializedMessage)
            }
            TransferFormat.Text -> {
                val text = serializedMessage.decodeToString()
                transport.sendText(text)
            }
        }
        logger.log(Logger.Severity.INFO, "Sent hub data: $message", null)
    } catch (ex: Exception) {
        logger.log(Logger.Severity.ERROR, "Failed to send hub data: $message", ex)
        throw ex
    }
}
