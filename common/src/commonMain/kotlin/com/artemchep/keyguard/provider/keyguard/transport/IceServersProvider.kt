package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.util.readBodyAtMostOrNull
import com.artemchep.keyguard.feature.p2psync.transport.IceServer
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches the STUN and TURN servers from `POST <relay>/v1/ice-servers` and caches them
 * for half their TTL. A connection keeps the credentials it started with, and TURN drops
 * it once they expire, so each one starts with at least half of the TTL left; a session
 * over TURN moves to a new connection, with fresh credentials, before they expire, see
 * [IceServersGrant.msUntilRefresh]. When the relay can not be reached, or answers with
 * something else, it returns an empty list, so WebRTC still finds host candidates on the
 * local network, and asks again after a minute.
 *
 * A request carries the ticket of a joined relay connection: the relay gives TURN
 * credentials only to a request with a live ticket, and STUN only with a short TTL to the
 * others. So servers fetched without a ticket are fetched again once one is given.
 */
class IceServersProvider(
    private val httpClient: HttpClient,
    private val endpoint: RelayEndpoint,
    /** A monotonic clock in milliseconds. */
    private val nowMs: () -> Long = monotonicMs(),
) {
    companion object {
        /** A failed fetch is retried after this long. */
        const val RETRY_AFTER_FAILURE_MS: Long = 60_000L

        /** The longest TTL believed; the relay allows at most a day. */
        private const val MAX_TTL_SECONDS: Long = 24L * 60L * 60L

        /** The largest response read, in bytes; the rest of a larger one is never buffered. */
        private const val MAX_BODY_BYTES = 64L * 1024L

        private const val MILLIS_PER_SECOND = 1_000L

        private val SCHEMES = listOf("stun:", "stuns:", "turn:", "turns:")

        private val json = Json {
            ignoreUnknownKeys = true
        }
    }

    private val mutex = Mutex()

    private var cached: List<IceServer> = emptyList()

    /** When the relay issued [cached], and for how long, by [nowMs]. */
    private var issuedAt: Long = 0L
    private var ttlMs: Long = 0L

    /** When [cached] must be fetched again. */
    private var refreshAt: Long? = null

    /** Whether a fetch with a ticket was tried since [cached] was fetched without TURN. */
    private var ticketTried = false

    /**
     * Returns the ICE servers, from the cache while their credentials are fresh, with when
     * their TURN credentials expire. A fetch sends [ticket], from [RelayState.Joined], to the
     * relay's own ICE endpoint only.
     */
    suspend fun grant(ticket: String? = null): IceServersGrant = mutex.withLock {
        val now = nowMs()
        val sent = ticket?.takeIf { endpoint.iceServersAtRelay }
        val fresh = refreshAt?.let { now < it } == true
        // Servers without TURN, fetched without a ticket: a ticket may get TURN.
        val upgradable = sent != null && !ticketTried && cached.none { it.isTurn() }
        if (!fresh || upgradable) {
            val response = fetch(sent)
            ticketTried = sent != null
            if (response != null) {
                cached = response.servers
                issuedAt = now
                ttlMs = response.ttlSeconds.coerceAtMost(MAX_TTL_SECONDS) * MILLIS_PER_SECOND
                refreshAt = now + ttlMs / 2
            } else if (!fresh) {
                cached = emptyList()
                issuedAt = now
                ttlMs = 0L
                refreshAt = now + RETRY_AFTER_FAILURE_MS
            }
        }
        IceServersGrant(cached, issuedAtMs = issuedAt, ttlMs = ttlMs, nowMs = nowMs)
    }

    private class Fetched(val servers: List<IceServer>, val ttlSeconds: Long)

    private suspend fun fetch(ticket: String?): Fetched? = runCatchingUnlessCancelled {
        // A plain post() buffers the whole body before returning, so the limit below would
        // come too late: the body is streamed instead.
        httpClient.preparePost(endpoint.iceServersUrl) {
            accept(ContentType.Application.Json)
            relayRequest(endpoint.iceToken)
            ticket?.let {
                val body = json.encodeToString(IceServersRequest.serializer(), IceServersRequest(it))
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute null
            response.readBodyAtMostOrNull(MAX_BODY_BYTES)
                ?.decodeToString()
                ?.let(::parse)
        }
    }.getOrNull()

    // A SerializationException is an IllegalArgumentException.
    private fun parse(text: String): Fetched? {
        val response = try {
            json.decodeFromString<IceServersResponse>(text)
        } catch (expected: IllegalArgumentException) {
            null
        }
        return response
            ?.takeIf { it.ttlSeconds > 0 }
            ?.let { Fetched(it.iceServers.mapNotNull(::toServer), it.ttlSeconds) }
    }

    private fun toServer(server: IceServerJson): IceServer? {
        val hasCredentials = !server.username.isNullOrEmpty() && !server.credential.isNullOrEmpty()
        val urls = server.urls.filter { url ->
            val scheme = SCHEMES.firstOrNull { url.startsWith(it, ignoreCase = true) }
            // TURN without credentials never allocates.
            scheme != null && (hasCredentials || !scheme.startsWith("turn"))
        }
        return IceServer(
            urls = urls,
            username = server.username.takeIf { hasCredentials },
            credential = server.credential.takeIf { hasCredentials },
        ).takeIf { urls.isNotEmpty() }
    }

    @Serializable
    private class IceServersRequest(
        val ticket: String,
    )

    @Serializable
    private class IceServersResponse(
        val iceServers: List<IceServerJson>,
        val ttlSeconds: Long,
    )

    @Serializable
    private class IceServerJson(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null,
    )
}

/**
 * ICE servers as an [IceServersProvider] handed them out: the relay issued the TURN
 * credentials among them at [issuedAtMs], valid for [ttlMs], by the monotonic clock [nowMs]
 * of the provider. coturn checks them on every refresh of an allocation, so a connection
 * relayed through TURN drops once they expire.
 */
class IceServersGrant(
    val servers: List<IceServer>,
    val issuedAtMs: Long,
    val ttlMs: Long,
    private val nowMs: () -> Long,
) {
    companion object {
        /** A session over TURN moves to a new connection at this share of the lifetime of its credentials. */
        const val REFRESH_PERCENT: Long = 90L

        private const val PERCENT: Long = 100L
    }

    /** `true` if the servers hold TURN credentials. */
    val hasTurn: Boolean get() = servers.any { it.isTurn() }

    /**
     * How long until the TURN credentials reach [REFRESH_PERCENT] of their lifetime, counted
     * from when they were issued, at least 0; `null` without TURN credentials.
     */
    fun msUntilRefresh(): Long? = if (hasTurn) {
        (issuedAtMs + ttlMs * REFRESH_PERCENT / PERCENT - nowMs()).coerceAtLeast(0L)
    } else {
        null
    }
}

private fun IceServer.isTurn(): Boolean = urls.any { it.startsWith("turn", ignoreCase = true) }
