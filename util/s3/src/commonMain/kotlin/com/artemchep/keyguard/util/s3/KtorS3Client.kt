package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.foundation.crypto.HashState
import com.artemchep.keyguard.util.foundation.crypto.createMd5
import com.artemchep.keyguard.util.foundation.crypto.createSha256
import com.artemchep.keyguard.util.s3.internal.S3Addressing
import com.artemchep.keyguard.util.s3.internal.S3ErrorBody
import com.artemchep.keyguard.util.s3.internal.S3ErrorContext
import com.artemchep.keyguard.util.s3.internal.S3ListBucketResult
import com.artemchep.keyguard.util.s3.internal.S3RequestTarget
import com.artemchep.keyguard.util.s3.internal.S3Xml
import com.artemchep.keyguard.util.s3.internal.STATUS_NOT_FOUND
import com.artemchep.keyguard.util.s3.internal.STATUS_OK
import com.artemchep.keyguard.util.s3.internal.STATUS_PARTIAL_CONTENT
import com.artemchep.keyguard.util.s3.internal.STATUS_PRECONDITION_FAILED
import com.artemchep.keyguard.util.s3.internal.isSuccessStatus
import com.artemchep.keyguard.util.s3.internal.limitedS3BodySource
import com.artemchep.keyguard.util.s3.internal.mapS3Error
import com.artemchep.keyguard.util.s3.internal.mapS3TransportException
import com.artemchep.keyguard.util.s3.internal.parseHttpDateOrNull
import com.artemchep.keyguard.util.s3.internal.readS3ResponseText
import com.artemchep.keyguard.util.s3.internal.requestS3StreamingSource
import com.artemchep.keyguard.util.s3.internal.s3BodySource
import com.artemchep.keyguard.util.s3.internal.toHttpRangeHeader
import com.artemchep.keyguard.util.s3.internal.trimToNull
import com.artemchep.keyguard.util.s3.internal.validateS3BodyEncoding
import com.artemchep.keyguard.util.s3.internal.validateS3ReadHeaders
import com.artemchep.keyguard.util.s3.internal.validatingS3BodySource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readTo
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * An [S3Client] that sends requests with the [httpClient].
 *
 * The [httpClient] must not follow redirects: a redirect changes the signed
 * host and path, so it is reported as [S3Exception.WrongRegion] instead. It
 * must not cache or decompress responses either, also not in its engine. To
 * share the engine of another client without its plugins, use
 * `HttpClient(engine) { followRedirects = false }`. A retry plugin replays a
 * request with its original signature, which S3 rejects after 15 minutes.
 */
@Suppress("TooManyFunctions")
class KtorS3Client(
    private val httpClient: HttpClient,
    config: S3ClientConfig,
    private val closeHttpClient: Boolean = false,
    private val clock: Clock = Clock.System,
) : S3Client {
    init {
        require(httpClient.pluginOrNull(HttpRedirect) == null) {
            "S3 requests need an HTTP client that does not follow redirects."
        }
        require(httpClient.pluginOrNull(HttpCache) == null) {
            "S3 requests need an HTTP client that does not cache responses."
        }
        require(httpClient.pluginOrNull(ContentEncoding) == null) {
            "S3 requests need an HTTP client that does not decompress object bytes."
        }
    }

    private val addressing = S3Addressing(config)
    private val signer = S3SigV4Signer(
        credentials = config.credentials,
        region = addressing.region,
    )
    private val userAgent: String? = config.userAgent

    /** Learned from server responses; races only cause an extra clock correction. */
    @Volatile
    private var clockOffsetMillis = 0L

    override suspend fun headObject(
        key: String,
    ): S3Object? {
        val target = addressing.objectTarget(key)
        return withClockSkewRetry {
            request(S3Operation.Head, key, HttpMethod.Head, target) { response ->
                val status = response.status.value
                when {
                    status.isSuccessStatus() -> response.toS3Object(
                        key = key,
                        size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    )
                    status == STATUS_NOT_FOUND -> {
                        // A HEAD response has no body, so most servers do not
                        // tell a missing bucket apart from a missing object.
                        val error = response.toException(S3Operation.Head, key, error = null)
                        if (error !is S3Exception.NotFound) {
                            throw error
                        }
                        null
                    }
                    else -> throw response.toException(S3Operation.Head, key, error = null)
                }
            }
        }
    }

    override suspend fun getObject(
        key: String,
        range: S3ByteRange?,
    ): Source {
        val target = addressing.objectTarget(key)
        val signedHeaders = range
            ?.let { mapOf(HEADER_RANGE to it.toHttpRangeHeader()) }
            .orEmpty()
        return withClockSkewRetry {
            httpClient.requestS3StreamingSource(
                operation = S3Operation.Get,
                key = key,
                url = target.url,
                configure = {
                    setUpRequest(HttpMethod.Get, target, S3SigV4Signer.EMPTY_PAYLOAD_SHA256, signedHeaders)
                },
            ) { response -> response.toObjectSource(key, range) }
        }
    }

    private suspend fun HttpResponse.toObjectSource(
        key: String,
        range: S3ByteRange?,
    ): Source {
        // A server may ignore the range and send the whole object, which
        // still starts with the requested bytes when the range does.
        val wholeObject = range != null && range.offset == 0L && status.value == STATUS_OK
        val expected = if (range == null || wholeObject) STATUS_OK else STATUS_PARTIAL_CONTENT
        if (status.value != expected) {
            throw unexpectedReadStatus(key, range)
        }
        val expectedSize = try {
            validateS3BodyEncoding(call.attributes, key)
            validateS3ReadHeaders(headers, key, range.takeUnless { wholeObject })
        } catch (e: S3Exception) {
            bodyAsChannel().cancel(CancellationException("Rejected inconsistent S3 response"))
            throw e
        }
        val upstream = s3BodySource(S3Operation.Get, key)
        val limit = range?.length?.takeIf { wholeObject }
            ?: return validatingS3BodySource(upstream, key, expectedSize)
        return validatingS3BodySource(
            upstream = limitedS3BodySource(upstream, limit),
            key = key,
            expectedSize = expectedSize?.coerceAtMost(limit),
        )
    }

    private suspend fun HttpResponse.unexpectedReadStatus(
        key: String,
        range: S3ByteRange?,
    ): S3Exception = if (range != null && status.value == STATUS_OK) {
        // The server ignored the range and sent the whole object, which
        // does not start with the requested bytes.
        bodyAsChannel().cancel(CancellationException("Rejected unranged S3 response"))
        S3Exception.Protocol(
            operation = S3Operation.Get,
            key = key,
            message = "server ignored the requested range",
            statusCode = status.value,
        )
    } else {
        toException(S3Operation.Get, key)
    }

    override suspend fun putObject(
        key: String,
        contentLength: Long,
        precondition: S3WritePrecondition?,
        body: suspend (Sink) -> Unit,
    ): S3Object {
        require(contentLength in 0L..MAX_SINGLE_PUT_BYTES) {
            "S3 single upload must be between 0 and $MAX_SINGLE_PUT_BYTES bytes."
        }
        val target = addressing.objectTarget(key)
        // TLS already protects the body in transit, so its hash is only
        // signed over plain HTTP.
        val hashes = hashPayload(contentLength, body, signPayload = !addressing.isHttps)
        val upload = Upload(
            key = key,
            target = target,
            contentLength = contentLength,
            payloadSha256 = hashes.sha256Hex ?: S3SigV4Signer.UNSIGNED_PAYLOAD,
            // Lets the server reject a different body; buckets with Object
            // Lock retention also require it.
            contentMd5 = hashes.md5Base64,
            body = body,
        )
        // Only the server can enforce a write precondition atomically.
        return withClockSkewRetry {
            put(upload, precondition)
        }
    }

    private class Upload(
        val key: String,
        val target: S3RequestTarget,
        val contentLength: Long,
        val payloadSha256: String,
        val contentMd5: String,
        val body: suspend (Sink) -> Unit,
    )

    private class PayloadHashes(
        val md5Base64: String,
        val sha256Hex: String?,
    )

    private suspend fun put(
        upload: Upload,
        precondition: S3WritePrecondition?,
    ): S3Object {
        return putOnce(upload, precondition) { response ->
            val status = response.status.value
            if (status.isSuccessStatus()) {
                return@putOnce S3Object(
                    key = upload.key,
                    size = upload.contentLength,
                    lastModified = null,
                    etag = response.headers[HttpHeaders.ETag]?.trimToNull(),
                )
            }

            val error = response.readErrorBody()
            throw if (precondition != null && status == STATUS_PRECONDITION_FAILED) {
                precondition.toConflict(upload.key, status, error?.code)
            } else {
                response.toException(S3Operation.Put, upload.key, error)
                    .deletedObjectAsConflict(precondition)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> putOnce(
        upload: Upload,
        precondition: S3WritePrecondition?,
        transform: suspend (HttpResponse) -> T,
    ): T {
        val signedHeaders = buildMap {
            put(HEADER_CONTENT_MD5, upload.contentMd5)
            when (precondition) {
                null -> Unit
                S3WritePrecondition.IfNoneMatch -> put(HEADER_IF_NONE_MATCH, "*")
                is S3WritePrecondition.IfMatch -> put(HEADER_IF_MATCH, precondition.etag)
            }
        }
        // Engines report a failed body as a transport failure, if at all:
        // Darwin waits for the rest of the declared length instead.
        var bodyFailure: Exception? = null
        return try {
            coroutineScope {
                val call = this
                request(
                    operation = S3Operation.Put,
                    key = upload.key,
                    method = HttpMethod.Put,
                    target = upload.target,
                    payloadSha256 = upload.payloadSha256,
                    signedHeaders = signedHeaders,
                    transform = transform,
                    block = {
                        // A retried PUT could report a false conflict against its own
                        // first attempt.
                        retry {
                            noRetry()
                        }
                        setBody(
                            object : OutgoingContent.WriteChannelContent() {
                                override val contentLength: Long = upload.contentLength
                                override val contentType: ContentType = ContentType.Application.OctetStream

                                override suspend fun writeTo(channel: ByteWriteChannel) {
                                    val transport = FailureRecordingSink(channel.asSink())
                                    try {
                                        val exact = ExactLengthSink(transport, upload.contentLength)
                                        val sink = exact.buffered()
                                        upload.body(sink)
                                        sink.flush()
                                        exact.checkComplete()
                                    } catch (e: Exception) {
                                        // A failed channel is a transport failure, which
                                        // the engine reports with the response, if any.
                                        if (e !is CancellationException && transport.failure == null) {
                                            bodyFailure = e
                                            call.cancel(CancellationException("S3 upload body failed", e))
                                        }
                                        throw e
                                    }
                                }
                            },
                        )
                    },
                )
            }
        } catch (e: Exception) {
            val failure = bodyFailure
            if (failure == null || !currentCoroutineContext().isActive) {
                throw e
            }
            throw failure
        }
    }

    /**
     * Reads the body once to hash it. Fails before the upload if the body
     * does not produce exactly [contentLength] bytes.
     */
    private suspend fun hashPayload(
        contentLength: Long,
        body: suspend (Sink) -> Unit,
        signPayload: Boolean,
    ): PayloadHashes {
        // Hashes that are not finalized must be released.
        val pending = mutableListOf<HashState>()
        try {
            val md5 = createMd5().also(pending::add)
            val sha256 = if (signPayload) createSha256().also(pending::add) else null
            val chunk = ByteArray(HASH_CHUNK_SIZE)
            val hashing = object : RawSink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    var remaining = byteCount
                    while (remaining > 0L) {
                        val length = minOf(remaining, chunk.size.toLong()).toInt()
                        source.readTo(chunk, startIndex = 0, endIndex = length)
                        pending.forEach { it.update(chunk, 0, length) }
                        remaining -= length
                    }
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
            val exact = ExactLengthSink(hashing, contentLength)
            val sink = exact.buffered()
            body(sink)
            sink.flush()
            exact.checkComplete()
            return PayloadHashes(
                md5Base64 = Base64.encode(md5.finishPending(pending)),
                sha256Hex = sha256?.finishPending(pending)?.toHexString(),
            )
        } finally {
            pending.forEach { it.close() }
        }
    }

    private fun HashState.finishPending(
        pending: MutableList<HashState>,
    ): ByteArray = doFinal()
        .also { pending.remove(this) }

    override suspend fun listObjects(
        prefix: String,
        delimiter: String?,
        continuationToken: String?,
        maxKeys: Int?,
    ): S3ListPage {
        require(maxKeys == null || maxKeys in 0..MAX_LIST_KEYS) {
            "S3 max-keys must be between 0 and $MAX_LIST_KEYS."
        }
        val query = buildList {
            add("list-type" to "2")
            add("encoding-type" to "url")
            if (prefix.isNotEmpty()) {
                add("prefix" to prefix)
            }
            delimiter?.let { add("delimiter" to it) }
            continuationToken?.let { token ->
                val marker = token.removePrefixOrNull(V1_MARKER_TOKEN_PREFIX)
                if (marker != null) {
                    add("marker" to marker)
                } else {
                    add("continuation-token" to token)
                }
            }
            maxKeys?.let { add("max-keys" to it.toString()) }
        }
        val target = addressing.bucketTarget(query)
        val result = withClockSkewRetry {
            request(S3Operation.List, null, HttpMethod.Get, target) { response ->
                if (!response.status.value.isSuccessStatus()) {
                    throw response.toException(S3Operation.List, null)
                }
                parseListResponse(response)
            }
        }
        return S3ListPage(
            objects = result.objects,
            commonPrefixes = result.commonPrefixes,
            nextContinuationToken = result.nextTokenOrNull(continuationToken),
        )
    }

    private suspend fun parseListResponse(
        response: HttpResponse,
    ): S3ListBucketResult {
        return try {
            val text = response.readS3ResponseText(MAX_LIST_RESPONSE_CHARS + 1)
            require(text.length <= MAX_LIST_RESPONSE_CHARS) {
                "Listing response is too large."
            }
            S3Xml.parseListBucketResult(text)
        } catch (e: IllegalArgumentException) {
            throw S3Exception.Protocol(
                operation = S3Operation.List,
                key = null,
                message = "could not parse ListBucketResult",
                cause = e,
            )
        }
    }

    /** Guards against servers that would make a paginated listing loop forever. */
    private fun S3ListBucketResult.nextTokenOrNull(
        requestToken: String?,
    ): String? {
        if (!isTruncated) {
            return null
        }
        // A server that ignores list-type=2 continues after a marker. It is
        // passed through the opaque token to keep the S3Client API simple.
        val token = nextContinuationToken
            ?: nextMarker?.let { V1_MARKER_TOKEN_PREFIX + it }
        if (token == null || token == requestToken) {
            throw S3Exception.Protocol(
                operation = S3Operation.List,
                key = null,
                message = "truncated listing has no new continuation token",
            )
        }
        return token
    }

    override suspend fun deleteObject(
        key: String,
    ) {
        val target = addressing.objectTarget(key)
        withClockSkewRetry {
            request(S3Operation.Delete, key, HttpMethod.Delete, target) { response ->
                if (!response.status.value.isSuccessStatus()) {
                    val text = response.readErrorText()
                    val error = response.toException(S3Operation.Delete, key, text?.let(S3Xml::parseErrorOrNull))
                    // A 404 page of a proxy in front of the server is not a
                    // missing object.
                    val missingObject = error is S3Exception.NotFound &&
                        (error.errorCode == ERROR_NO_SUCH_KEY || text.isNullOrBlank())
                    if (!missingObject) {
                        throw error
                    }
                }
            }
        }
    }

    override suspend fun close() {
        if (closeHttpClient) {
            httpClient.close()
        }
    }

    /**
     * Retries once after correcting the clock offset when the server rejects
     * a request because the device clock differs from the server clock.
     */
    private suspend fun <T> withClockSkewRetry(
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: S3Exception.ClockSkew) {
        val serverTime = e.serverTime
            ?: throw e
        clockOffsetMillis = (serverTime - clock.now()).inWholeMilliseconds
        block()
    }

    private fun now(): Instant = clock.now() + clockOffsetMillis.milliseconds

    private suspend fun <T> request(
        operation: S3Operation,
        key: String?,
        method: HttpMethod,
        target: S3RequestTarget,
        payloadSha256: String = S3SigV4Signer.EMPTY_PAYLOAD_SHA256,
        signedHeaders: Map<String, String> = emptyMap(),
        block: HttpRequestBuilder.() -> Unit = {},
        transform: suspend (HttpResponse) -> T,
    ): T = mapS3TransportException(operation, key) {
        httpClient.prepareRequest(target.url) {
            setUpRequest(method, target, payloadSha256, signedHeaders)
            block()
        }.execute(transform)
    }

    private fun HttpRequestBuilder.setUpRequest(
        method: HttpMethod,
        target: S3RequestTarget,
        payloadSha256: String,
        signedHeaders: Map<String, String>,
    ) {
        this.method = method
        // Error statuses are mapped to typed S3 exceptions instead.
        expectSuccess = false
        val signingTime = now()
        attributes.put(REQUEST_SIGNING_TIME, signingTime)
        val authorization = signer.sign(
            method = method.value,
            canonicalUri = target.canonicalUri,
            canonicalQuery = target.canonicalQuery,
            headers = signedHeaders + (HEADER_HOST to target.host),
            payloadSha256Hex = payloadSha256,
            now = signingTime,
        )
        (signedHeaders + authorization).forEach { (name, value) ->
            header(name, value)
        }
        // Unsigned headers: HTTP plugins and engines are free to change them.
        // Engines decompress transparently and then drop the Content-Length
        // that the read validation relies on.
        header(HttpHeaders.AcceptEncoding, "identity")
        userAgent?.let { value ->
            header(HttpHeaders.UserAgent, value)
        }
    }

    private suspend fun HttpResponse.toException(
        operation: S3Operation,
        key: String?,
    ): S3Exception = toException(operation, key, readErrorBody())

    private fun HttpResponse.toException(
        operation: S3Operation,
        key: String?,
        error: S3ErrorBody?,
    ): S3Exception = mapS3Error(
        S3ErrorContext(
            operation = operation,
            key = key,
            statusCode = status.value,
            error = error,
            headers = headers,
            // Another request may have corrected the shared clock offset
            // while this request was waiting for its response.
            now = call.attributes[REQUEST_SIGNING_TIME],
            signingRegion = addressing.region,
        ),
    )

    private suspend fun HttpResponse.readErrorBody(): S3ErrorBody? = readErrorText()
        ?.let(S3Xml::parseErrorOrNull)

    private suspend fun HttpResponse.readErrorText(): String? = try {
        readS3ResponseText(MAX_ERROR_RESPONSE_CHARS)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun HttpResponse.toS3Object(
        key: String,
        size: Long?,
    ): S3Object = S3Object(
        key = key,
        size = size,
        lastModified = headers[HttpHeaders.LastModified]?.let(::parseHttpDateOrNull),
        etag = headers[HttpHeaders.ETag]?.trimToNull(),
    )

    private companion object {
        private val REQUEST_SIGNING_TIME = AttributeKey<Instant>("S3SigningTime")

        private const val HEADER_HOST = "host"
        private const val HEADER_RANGE = "range"
        private const val HEADER_IF_MATCH = "if-match"
        private const val HEADER_IF_NONE_MATCH = "if-none-match"
        private const val HEADER_CONTENT_MD5 = "content-md5"

        private const val ERROR_NO_SUCH_KEY = "NoSuchKey"

        /** Marks a continuation token that holds a version 1 listing marker. */
        private const val V1_MARKER_TOKEN_PREFIX = "keyguard-v1-marker:"

        /** The largest object a single PUT may upload. */
        private const val MAX_SINGLE_PUT_BYTES = 5L * 1024L * 1024L * 1024L
        private const val MAX_LIST_KEYS = 1000
        private const val MAX_LIST_RESPONSE_CHARS = 8 * 1024 * 1024
        private const val MAX_ERROR_RESPONSE_CHARS = 64 * 1024
        private const val HASH_CHUNK_SIZE = 64 * 1024
    }
}

private fun String.removePrefixOrNull(
    prefix: String,
): String? = if (startsWith(prefix)) substring(prefix.length) else null

/** The object that an `If-Match` write expects was deleted meanwhile. */
private fun S3Exception.deletedObjectAsConflict(
    precondition: S3WritePrecondition?,
): S3Exception = if (precondition is S3WritePrecondition.IfMatch && this is S3Exception.NotFound) {
    S3Exception.PreconditionFailed(S3Operation.Put, key, statusCode, errorCode, cause = this)
} else {
    this
}

private fun S3WritePrecondition?.toConflict(
    key: String,
    statusCode: Int?,
    errorCode: String?,
): S3Exception = when (this) {
    S3WritePrecondition.IfNoneMatch -> S3Exception.AlreadyExists(S3Operation.Put, key, statusCode, errorCode)
    else -> S3Exception.PreconditionFailed(S3Operation.Put, key, statusCode, errorCode)
}

/** Remembers the failure of [upstream], to tell it apart from a failure of the body. */
@Suppress("TooGenericExceptionCaught")
private class FailureRecordingSink(
    private val upstream: RawSink,
) : RawSink {
    @Volatile
    var failure: Throwable? = null
        private set

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) = recordFailure { upstream.write(source, byteCount) }

    override fun flush() = recordFailure { upstream.flush() }

    override fun close() = recordFailure { upstream.close() }

    private inline fun recordFailure(
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            failure = e
            throw e
        }
    }
}

/** Fails an upload whose body does not produce exactly [length] bytes, before any extra byte is sent. */
private class ExactLengthSink(
    private val upstream: RawSink,
    private val length: Long,
) : RawSink {
    private var written = 0L

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(byteCount <= length - written) {
            "S3 upload body produced more than $length bytes."
        }
        upstream.write(source, byteCount)
        written += byteCount
    }

    override fun flush() = upstream.flush()

    override fun close() = upstream.close()

    fun checkComplete() = check(written == length) {
        "S3 upload body produced $written bytes instead of $length."
    }
}
