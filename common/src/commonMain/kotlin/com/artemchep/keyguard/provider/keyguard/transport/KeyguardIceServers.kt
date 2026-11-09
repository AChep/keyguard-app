package com.artemchep.keyguard.provider.keyguard.transport

import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The [IceServersProvider] of each relay, kept for the life of the app so that every
 * session and enrollment with the same relay shares its cached credentials.
 */
class KeyguardIceServers(
    private val httpClient: HttpClient,
) {
    private data class Key(
        val iceServersUrl: String,
        val token: String?,
        /** Whether a request may carry a ticket, see [RelayEndpoint.iceServersAtRelay]. */
        val atRelay: Boolean,
    )

    private val lock = SynchronizedObject()

    private val providers = mutableMapOf<Key, IceServersProvider>()

    /** Returns the provider of the ICE servers of [endpoint]. */
    fun get(endpoint: RelayEndpoint): IceServersProvider = synchronized(lock) {
        val key = Key(
            iceServersUrl = endpoint.iceServersUrl,
            token = endpoint.iceToken,
            atRelay = endpoint.iceServersAtRelay,
        )
        providers.getOrPut(key) {
            IceServersProvider(
                httpClient = httpClient,
                endpoint = endpoint,
            )
        }
    }
}
