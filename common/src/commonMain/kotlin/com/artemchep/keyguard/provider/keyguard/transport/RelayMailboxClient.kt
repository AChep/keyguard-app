package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.util.isLowerHexDigit
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxLimits
import io.ktor.client.HttpClient
import io.ktor.client.plugins.CurlUserAgent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An item the relay keeps: the [item] id of the [kind], asked for under the [ticket] of a
 * joined relay connection, see [RelayState.Joined.ticket]. The relay serves only requests
 * with a live ticket.
 */
class RelayMailboxRef(
    val ticket: String,
    val kind: MailboxKind,
    val item: String,
) {
    init {
        require(RelayCodec.isTicket(ticket)) { "Not a relay ticket" }
        require(item.length == MailboxLimits.ITEM_ID_LENGTH && item.all(Char::isLowerHexDigit)) { "Not an item id" }
    }

    override fun toString(): String = "RelayMailboxRef(${kind.wireName})"
}

/** Why a relay refused a request for an item, or why the request failed. */
enum class RelayMailboxError {
    /** 400: the relay could not read the request. */
    BAD_REQUEST,

    /** 401: a missing or wrong token. */
    UNAUTHORIZED,

    /** 403: the ticket is not one of a joined connection. */
    TICKET_REQUIRED,

    /** 408: the upload took too long. */
    TIMEOUT,

    /** 429: too many requests or bytes from this address. */
    RATE_LIMITED,

    /** 507: the relay has no room for the item. */
    STORAGE_FULL,

    /** 503: the relay keeps no items for now. */
    STORAGE_UNAVAILABLE,

    /** The network failed. */
    NETWORK,

    /** The relay answered with something else than the protocol says. */
    BAD_RESPONSE,
}

/** How a request for an item went, see [RelayMailbox]. */
sealed interface RelayMailboxResult {
    /** A PUT stored the item, until [expiresAt] in Unix seconds if the relay said; or a DELETE removed it. */
    data class Done(val expiresAt: Long? = null) : RelayMailboxResult

    /**
     * The relay holds the item with the [tag], until [expiresAt] in Unix seconds if it said:
     * the [body] for a GET, `null` for a HEAD.
     */
    class Found(
        val tag: String,
        val expiresAt: Long?,
        val body: ByteArray?,
    ) : RelayMailboxResult

    /** 304: the item still has the tag of the `If-None-Match`. */
    data object NotModified : RelayMailboxResult

    /** 404: the relay holds no such item: it was never posted, it expired, or it was evicted. */
    data object NotFound : RelayMailboxResult

    /** 412: a write-once PUT found the item there already, see [RelayMailbox.put]. */
    data object Exists : RelayMailboxResult

    /** 413: the item is larger than the relay takes. */
    data object TooLarge : RelayMailboxResult

    /** 429, 503 or 507: no request to the relay should go out for the [retryAfterSeconds] it names. */
    data class RetryLater(
        val error: RelayMailboxError,
        val retryAfterSeconds: Long?,
    ) : RelayMailboxResult

    /** The request failed with [error]; trying again later may help. */
    data class Failed(val error: RelayMailboxError) : RelayMailboxResult
}

/**
 * The items a relay keeps for the devices that are offline, `{relay}/v1/mailbox/{kind}`. An
 * item id is a capability, so it goes in a header, never in the URL, which reverse proxies
 * log.
 */
interface RelayMailbox {
    /**
     * Stores the sealed [body] with the [tag] for [ttlSeconds] at most, in place of the item
     * there; with [ifAbsent], only if there is none, else it is [RelayMailboxResult.Exists].
     */
    suspend fun put(
        ref: RelayMailboxRef,
        tag: String,
        ttlSeconds: Long,
        body: ByteArray,
        ifAbsent: Boolean = false,
    ): RelayMailboxResult

    /**
     * Returns the item, unless it still has the tag [ifNoneMatch]. A body over [maxBytes]
     * is never read whole: it fails as [RelayMailboxError.BAD_RESPONSE].
     *
     * With a [download] of the item, the body goes into it as it arrives, and a request cut
     * short, or cancelled, leaves the bytes there: the next request with it asks only for
     * the rest, while the item is the same upload. The [download] holds nothing once the
     * item is returned, and drops its bytes whenever an answer does not fit them.
     */
    suspend fun get(
        ref: RelayMailboxRef,
        ifNoneMatch: String?,
        maxBytes: Int,
        download: RelayMailboxDownload? = null,
    ): RelayMailboxResult

    /** Returns the tag of the item, without its body. */
    suspend fun head(ref: RelayMailboxRef): RelayMailboxResult

    /** Deletes the item. */
    suspend fun delete(ref: RelayMailboxRef): RelayMailboxResult
}

/** Returns the [RelayMailbox] of a relay. */
fun interface KeyguardRelayMailboxes {
    fun get(endpoint: RelayEndpoint): RelayMailbox

    companion object {
        /** No relay keeps items: every request fails as the network would. */
        val NONE: KeyguardRelayMailboxes = KeyguardRelayMailboxes { UnreachableRelayMailbox }
    }
}

/**
 * The [RelayMailboxClient]s of the relays, over the engine of the app's [httpClient]: its
 * connections, timeouts and trust settings, but none of its plugins. Its cache would take
 * the 304 the relay answers to a GET with `If-None-Match` for a broken cache entry and
 * fail the request, so a device that merged the state of a peer would fetch it never
 * again. Each request carries the token of its relay itself.
 */
class KtorKeyguardRelayMailboxes(
    httpClient: HttpClient,
) : KeyguardRelayMailboxes {
    /** Shares the engine, which it does not close. */
    private val httpClient = HttpClient(httpClient.engine) {
        CurlUserAgent()
    }

    override fun get(endpoint: RelayEndpoint): RelayMailbox = RelayMailboxClient(httpClient, endpoint)
}

private object UnreachableRelayMailbox : RelayMailbox {
    private val failure = RelayMailboxResult.Failed(RelayMailboxError.NETWORK)

    override suspend fun put(
        ref: RelayMailboxRef,
        tag: String,
        ttlSeconds: Long,
        body: ByteArray,
        ifAbsent: Boolean,
    ) = failure

    override suspend fun get(
        ref: RelayMailboxRef,
        ifNoneMatch: String?,
        maxBytes: Int,
        download: RelayMailboxDownload?,
    ) = failure

    override suspend fun head(ref: RelayMailboxRef) = failure

    override suspend fun delete(ref: RelayMailboxRef) = failure
}

/**
 * The [RelayMailbox] of the relay at [endpoint], over HTTP, with a client that caches
 * nothing, see [KtorKeyguardRelayMailboxes]. Every request carries the token
 * of the relay, if it has one, the ticket and the item id, see [RelayMailboxRef]. A body is
 * streamed and read up to its limit only, so a hostile relay can not fill the memory.
 */
class RelayMailboxClient(
    private val httpClient: HttpClient,
    private val endpoint: RelayEndpoint,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
) : RelayMailbox {
    companion object {
        const val HEADER_TICKET: String = "X-Keyguard-Ticket"
        const val HEADER_ITEM: String = "X-Keyguard-Item"
        const val HEADER_TAG: String = "X-Keyguard-Tag"
        const val HEADER_TTL: String = "X-Keyguard-Ttl"
        const val HEADER_EXPIRES: String = "X-Keyguard-Expires"

        /**
         * How long a request may take, body included: a little longer than the relay gives
         * one, 120 seconds, so a relay that trickles a body never holds a claim for good.
         */
        const val REQUEST_TIMEOUT_MS: Long = 150_000L
    }

    override suspend fun put(
        ref: RelayMailboxRef,
        tag: String,
        ttlSeconds: Long,
        body: ByteArray,
        ifAbsent: Boolean,
    ): RelayMailboxResult = exchange(
        method = HttpMethod.Put,
        ref = ref,
        configure = {
            header(HEADER_TAG, tag)
            header(HEADER_TTL, ttlSeconds.toString())
            if (ifAbsent) header(HttpHeaders.IfNoneMatch, "*")
            setBody(ByteArrayContent(body, ContentType.Application.OctetStream))
        },
        writeOnce = ifAbsent,
    ) { response -> RelayMailboxResult.Done(expiresAt = response.expiresAt()) }

    override suspend fun get(
        ref: RelayMailboxRef,
        ifNoneMatch: String?,
        maxBytes: Int,
        download: RelayMailboxDownload?,
    ): RelayMailboxResult {
        require(download == null || download.item == ref.item) { "Not a download of the item" }
        try {
            return exchange(
                method = HttpMethod.Get,
                ref = ref,
                configure = {
                    ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                    // The rest of the item, while it is the upload the bytes held are of.
                    download?.takeIf { it.isResumable }?.let {
                        header(HttpHeaders.Range, "bytes=${it.size}-")
                        header(HttpHeaders.IfRange, it.etag)
                    }
                },
                ranged = download != null,
            ) { response ->
                // Without a download, only a 200 holds the item: it is read whole.
                if (download == null && response.status != HttpStatusCode.OK) {
                    badResponse
                } else {
                    (download ?: RelayMailboxDownload(ref.item)).receive(response, maxBytes)
                }
            }
        } finally {
            // Bytes without an ETag can not resume: they never outlive their request.
            if (download != null && download.etag == null) download.reset()
        }
    }

    override suspend fun head(
        ref: RelayMailboxRef,
    ): RelayMailboxResult = exchange(
        method = HttpMethod.Head,
        ref = ref,
    ) { response ->
        tagOf(response)
            ?.let { RelayMailboxResult.Found(tag = it, expiresAt = response.expiresAt(), body = null) }
            ?: badResponse
    }

    override suspend fun delete(
        ref: RelayMailboxRef,
    ): RelayMailboxResult = exchange(
        method = HttpMethod.Delete,
        ref = ref,
    ) { RelayMailboxResult.Done() }

    /**
     * Sends a request for the item [ref] and returns what [onSuccess] makes of a 2xx answer,
     * or the refusal; a network failure, or a request that takes longer than
     * [requestTimeoutMs], is [RelayMailboxError.NETWORK]. A [ranged] GET may ask for a
     * range: [onSuccess] also reads a 416, a range past the end of the item. A 412 to a
     * [writeOnce] PUT is [RelayMailboxResult.Exists].
     */
    private suspend fun exchange(
        method: HttpMethod,
        ref: RelayMailboxRef,
        configure: HttpRequestBuilder.() -> Unit = {},
        ranged: Boolean = false,
        writeOnce: Boolean = false,
        onSuccess: suspend (HttpResponse) -> RelayMailboxResult,
    ): RelayMailboxResult = withTimeoutOrNull(requestTimeoutMs) {
        runCatchingUnlessCancelled {
            // A plain request buffers the whole body before returning: the body is streamed instead.
            httpClient.prepareRequest(endpoint.mailboxUrl(ref.kind)) {
                this.method = method
                relayRequest(endpoint.token)
                header(HEADER_TICKET, ref.ticket)
                header(HEADER_ITEM, ref.item)
                configure()
            }.execute { response ->
                val status = response.status
                when {
                    status == HttpStatusCode.NotModified -> RelayMailboxResult.NotModified
                    status.isSuccess() -> onSuccess(response)
                    ranged && status == HttpStatusCode.RequestedRangeNotSatisfiable -> onSuccess(response)
                    writeOnce && status == HttpStatusCode.PreconditionFailed -> RelayMailboxResult.Exists
                    else -> refusalOf(response)
                }
            }
        }.getOrElse { RelayMailboxResult.Failed(RelayMailboxError.NETWORK) }
    } ?: RelayMailboxResult.Failed(RelayMailboxError.NETWORK)

    /** The tag of the item of a 2xx answer, or `null` if it is not a 200 with a tag. */
    private fun tagOf(response: HttpResponse): String? = response.headers[HEADER_TAG]
        ?.takeIf { response.status == HttpStatusCode.OK && isTag(it) }

    private fun refusalOf(response: HttpResponse): RelayMailboxResult {
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        return when (response.status) {
            HttpStatusCode.BadRequest -> RelayMailboxResult.Failed(RelayMailboxError.BAD_REQUEST)
            HttpStatusCode.Unauthorized -> RelayMailboxResult.Failed(RelayMailboxError.UNAUTHORIZED)
            HttpStatusCode.Forbidden -> RelayMailboxResult.Failed(RelayMailboxError.TICKET_REQUIRED)
            HttpStatusCode.NotFound -> RelayMailboxResult.NotFound
            HttpStatusCode.RequestTimeout -> RelayMailboxResult.Failed(RelayMailboxError.TIMEOUT)
            HttpStatusCode.PayloadTooLarge -> RelayMailboxResult.TooLarge
            HttpStatusCode.TooManyRequests -> RelayMailboxResult.RetryLater(RelayMailboxError.RATE_LIMITED, retryAfter)
            HttpStatusCode.ServiceUnavailable ->
                RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_UNAVAILABLE, retryAfter)
            HttpStatusCode.InsufficientStorage ->
                RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_FULL, retryAfter)
            else -> badResponse
        }
    }

    private val badResponse = RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE)
}

/** The end of the lifetime of the item of [this] answer, in Unix seconds, if the relay said. */
internal fun HttpResponse.expiresAt(): Long? = headers[RelayMailboxClient.HEADER_EXPIRES]?.trim()?.toLongOrNull()

/** `true` if [value] is a tag: 16 bytes in lowercase hex. */
internal fun isTag(value: String): Boolean =
    value.length == MailboxLimits.TAG_LENGTH && value.all(Char::isLowerHexDigit)
