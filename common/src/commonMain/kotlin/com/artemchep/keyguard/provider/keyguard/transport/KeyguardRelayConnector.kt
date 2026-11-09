package com.artemchep.keyguard.provider.keyguard.transport

import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A connection to one relay room: [client] stays in the room while [run] runs. */
class KeyguardRelayConnection(
    val client: RelayClient,
    /** Keeps the client connected, reconnecting as needed, until cancelled. */
    val run: suspend () -> Unit,
)

/** Opens relay connections for the sessions and enrollments of sync groups. */
fun interface KeyguardRelayConnector {
    /** Returns a connection to [room] at [endpoint]; it connects once it runs. */
    fun open(
        endpoint: RelayEndpoint,
        room: RelayRoom,
    ): KeyguardRelayConnection
}

/**
 * Connects to relays over ktor WebSockets; [httpClient] must have them installed. The rooms
 * of one relay, by URL and token, share a WebSocket: see [RelayHub].
 */
class KtorKeyguardRelayConnector(
    private val httpClient: HttpClient,
) : KeyguardRelayConnector {
    private data class Key(
        val webSocketUrl: String,
        val token: String?,
    )

    /** Runs the WebSockets of every relay, for the life of the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = SynchronizedObject()

    private val hubs = mutableMapOf<Key, RelayHub>()

    override fun open(
        endpoint: RelayEndpoint,
        room: RelayRoom,
    ): KeyguardRelayConnection = hub(endpoint).open(room.id)

    private fun hub(endpoint: RelayEndpoint): RelayHub = synchronized(lock) {
        val key = Key(
            webSocketUrl = endpoint.webSocketUrl,
            token = endpoint.token,
        )
        hubs.getOrPut(key) {
            RelayHub(
                dialer = KtorRelayDialer(httpClient, endpoint),
                scope = scope,
            )
        }
    }
}
