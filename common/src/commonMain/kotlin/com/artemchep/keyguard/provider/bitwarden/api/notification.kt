package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.api.builder.headers
import com.artemchep.keyguard.provider.bitwarden.api.builder.notifications
import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubConnectionEvent
import com.artemchep.keyguard.util.signalr.HubConnectionState
import com.artemchep.keyguard.util.signalr.hubConnection
import com.artemchep.keyguard.util.signalr.internal.protocols.MessagePackHubProtocol
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

suspend fun notificationsHub(
    user: BitwardenToken,
    httpClient: HttpClient,
    onConnected: () -> Unit,
    onMessage: (Any) -> Unit,
    onHeartbeat: () -> Unit,
) {
    val env = user.env.back()
    val accessToken = requireNotNull(user.token)
        .accessToken

    hubConnection(
        url = env.notifications.hub,
        httpClient = httpClient,
        configure = {
            accessTokenProvider = {
                accessToken
            }
            headers = buildMap {
                headers(
                    env = env,
                ) { key, value ->
                    put(key, value)
                }
            }

            protocol = MessagePackHubProtocol()
            skipNegotiate = true
        },
    )
        .events()
        .onEach { event ->
            handleConnectionEvent(
                event = event,
                onConnected = onConnected,
                onMessage = onMessage,
                onHeartbeat = onHeartbeat,
            )
        }
        .collect()
}

private fun handleConnectionEvent(
    event: HubConnectionEvent,
    onConnected: () -> Unit,
    onMessage: (Any) -> Unit,
    onHeartbeat: () -> Unit,
) = when (event) {
    is HubConnectionEvent.StateChanged -> handleConnectionStateEvent(
        event = event,
        onConnected = onConnected,
    )

    is HubConnectionEvent.InvocationReceived -> handleConnectionInvocationEvent(
        event = event,
        onMessage = onMessage,
        onHeartbeat = onHeartbeat,
    )
}

private fun handleConnectionStateEvent(
    event: HubConnectionEvent.StateChanged,
    onConnected: () -> Unit,
) {
    when (event.state) {
        HubConnectionState.CONNECTED -> {
            logNotificationEvent("connected")
            onConnected()
        }

        HubConnectionState.DISCONNECTED -> {
            event.reason
                ?.toThrowableOrNull()
                ?.let { throw it }
        }

        HubConnectionState.CONNECTING,
        HubConnectionState.DISCONNECTING,
            -> Unit
    }
}

private fun handleConnectionInvocationEvent(
    event: HubConnectionEvent.InvocationReceived,
    onMessage: (Any) -> Unit,
    onHeartbeat: () -> Unit,
) {
    val invocation = event.invocation
    when (invocation.target) {
        "ReceiveMessage" -> invocation.arguments
            .firstOrNull()
            ?.let {
                logNotificationEvent("receive message")
                onMessage(it)
            }

        "Heartbeat" -> {
            logNotificationEvent("heartbeat")
            onHeartbeat()
        }
    }
}

private fun HubConnectionCloseReason.toThrowableOrNull(): Throwable? = when (this) {
    HubConnectionCloseReason.ClientStopped,
    HubConnectionCloseReason.TransportClosed,
        -> null

    is HubConnectionCloseReason.ServerClosed -> {
        val error = error
            ?: "Server closed"
        IllegalStateException(error)
    }

    is HubConnectionCloseReason.Failed -> {
        cause
    }
}

private fun logNotificationEvent(message: String) {
    recordLog("$message @ notifications")
}
