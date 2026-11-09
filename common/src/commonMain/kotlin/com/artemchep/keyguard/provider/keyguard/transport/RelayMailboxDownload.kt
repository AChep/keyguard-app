package com.artemchep.keyguard.provider.keyguard.transport

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * What a GET of the item [item] received so far, see [RelayMailbox.get]. A GET that was cut
 * short resumes from it: it asks for the rest with `Range` and `If-Range`, and the relay
 * sends only the rest while the item keeps the [etag], which every upload changes. Without
 * an `ETag`, as from an older relay, the bytes never outlive their request.
 *
 * One request uses it at a time.
 */
class RelayMailboxDownload(
    /** The id of the item, see [RelayMailboxRef.item]. */
    val item: String,
) {
    /** The strong `ETag` of the item the bytes are of, or `null` if the relay named none. */
    var etag: String? = null
        internal set

    /** The length of the whole item, if the relay named it. */
    var total: Long? = null
        internal set

    private val buffer = Buffer()

    /** The number of bytes received so far. */
    val size: Long get() = buffer.size

    /** `true` if it holds bytes that a GET resumes from. */
    val isResumable: Boolean get() = etag != null && buffer.size > 0L

    /** Drops what it holds: the next GET starts over. */
    fun reset() {
        buffer.clear()
        etag = null
        total = null
    }

    /** Appends the first [length] bytes of [bytes]. */
    internal fun append(bytes: ByteArray, length: Int) {
        buffer.write(bytes, startIndex = 0, endIndex = length)
    }

    /** Takes the whole item out: it holds nothing afterward. */
    internal fun take(): ByteArray = buffer.readByteArray().also { reset() }
}

/** The bytes read from a body at a time. */
private const val CHUNK_SIZE = 16 * 1024

/**
 * Reads the [response] to a GET with this download into it: all of a 200, which starts it
 * over, or the rest of the item from a 206. Returns the whole item once its body is
 * complete. A body cut short fails as [RelayMailboxError.NETWORK], and the bytes stay. An
 * answer that does not fit the bytes held drops them, and the next GET starts over.
 */
internal suspend fun RelayMailboxDownload.receive(
    response: HttpResponse,
    maxBytes: Int,
): RelayMailboxResult {
    val tag = response.headers[RelayMailboxClient.HEADER_TAG]?.takeIf(::isTag)
    val refusal = prepare(response, maxBytes)
    if (tag == null || refusal != null) {
        reset()
        return RelayMailboxResult.Failed(refusal ?: RelayMailboxError.BAD_RESPONSE)
    }
    return when (val error = readBody(response.bodyAsChannel(), maxBytes)) {
        null -> RelayMailboxResult.Found(tag = tag, expiresAt = response.expiresAt(), body = take())
        else -> RelayMailboxResult.Failed(error)
    }
}

/**
 * Gets ready for the body of the [response]; returns why the bytes held do not fit it, or
 * `null`. A 200 starts over with the `ETag` and the length it names; the length is at most
 * [maxBytes].
 */
private fun RelayMailboxDownload.prepare(
    response: HttpResponse,
    maxBytes: Int,
): RelayMailboxError? = when (response.status) {
    HttpStatusCode.OK -> {
        reset()
        etag = response.headers[HttpHeaders.ETag]?.takeIf(::isStrongEtag)
        total = response.contentLength()
        RelayMailboxError.BAD_RESPONSE.takeIf { (total ?: 0L) > maxBytes }
    }

    HttpStatusCode.PartialContent -> RelayMailboxError.NETWORK.takeUnless { resumes(response, maxBytes) }
    // The bytes held reach past the end of the item: they are not of it.
    HttpStatusCode.RequestedRangeNotSatisfiable -> RelayMailboxError.NETWORK
    else -> RelayMailboxError.BAD_RESPONSE
}

/**
 * `true` if the 206 [response] holds the rest of the item whose start this download holds:
 * it has the same `ETag`, and `Content-Range: bytes <size>-<length - 1>/<length>`, where
 * the length is the one known, if any, and at most [maxBytes].
 */
private fun RelayMailboxDownload.resumes(
    response: HttpResponse,
    maxBytes: Int,
): Boolean {
    val known = total
    val range = response.headers[HttpHeaders.ContentRange]
    val length = range
        ?.substringAfterLast('/')
        ?.toLongOrNull()
        ?.takeIf { it > size && it <= maxBytes && (known == null || it == known) }
    val fits = length != null &&
        etag != null &&
        response.headers[HttpHeaders.ETag] == etag &&
        range == "bytes $size-${length - 1}/$length"
    if (fits) total = length
    return fits
}

/**
 * Appends the [body] as it arrives, so a request cut short keeps what came. Returns `null`
 * once the item is whole, [RelayMailboxError.NETWORK] if the body ended before, or
 * [RelayMailboxError.BAD_RESPONSE], with the bytes dropped, if it runs past the item or
 * past [maxBytes].
 */
private suspend fun RelayMailboxDownload.readBody(
    body: ByteReadChannel,
    maxBytes: Int,
): RelayMailboxError? {
    val limit = total ?: maxBytes.toLong()
    val chunk = ByteArray(CHUNK_SIZE)
    var read = body.readAvailable(chunk)
    while (read >= 0 && size + read <= limit) {
        append(chunk, read)
        read = body.readAvailable(chunk)
    }
    return when {
        read >= 0 -> {
            reset()
            RelayMailboxError.BAD_RESPONSE
        }

        total?.let { size < it } == true -> RelayMailboxError.NETWORK
        else -> null
    }
}

/** `true` if [value] is a strong entity tag, `"…"`: only a strong one may go in `If-Range`. */
private fun isStrongEtag(value: String): Boolean = value.length > 1 && value.first() == '"' && value.last() == '"'
