package com.artemchep.keyguard.util.webdav

import com.artemchep.keyguard.util.webdav.internal.WebDavMultiStatusEntry
import com.artemchep.keyguard.util.webdav.internal.WebDavXml
import com.artemchep.keyguard.util.webdav.internal.hrefToWebDavPath
import com.artemchep.keyguard.util.webdav.internal.normalizeBaseCollectionUrl
import com.artemchep.keyguard.util.webdav.internal.parseHttpDateOrNull
import com.artemchep.keyguard.util.webdav.internal.resolveWebDavUrl
import com.artemchep.keyguard.util.webdav.internal.validateObjectPath
import com.artemchep.keyguard.util.webdav.internal.validatePrefixPath
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asSink
import io.ktor.utils.io.asSource
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

class KtorWebDavClient(
    private val httpClient: HttpClient,
    config: WebDavClientConfig,
    private val closeHttpClient: Boolean = false,
) : WebDavClient {
    private val baseUrl: Url = normalizeBaseCollectionUrl(config.baseUrl)
    private val authorization: WebDavAuthorization? = config.authorization
    private val userAgent: String? = config.userAgent
    private val noCache: Boolean = config.noCache
    private val writeStrategy: WebDavWriteStrategy = config.writeStrategy

    /*
     * Sticky per-client degradations, learned from server responses. Races
     * on these flags are benign: they only make later requests skip an
     * optional behavior that this server has already rejected once.
     */
    @Volatile
    private var moveUnsupported = false

    @Volatile
    private var conditionalMoveUnsupported = false

    @Volatile
    private var conditionalReplacePutUnsupported = false

    override suspend fun open(): WebDavOpenResult {
        val response = request(
            operation = WebDavOperation.Options,
            path = null,
            url = resolveWebDavUrl(baseUrl, path = "", collection = true),
            method = HttpMethod.Options,
        )
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Open,
            path = null,
            success = response.status.value in 200..299,
        )

        val base = propfindResource(
            path = "",
            operation = WebDavOperation.Open,
            collection = true,
        ) ?: throw WebDavException.NotFound(
            operation = WebDavOperation.Open,
            path = null,
        )
        if (!base.isCollection) {
            throw WebDavException.Protocol(
                operation = WebDavOperation.Open,
                path = null,
                message = "base URL is not a collection",
            )
        }

        return WebDavOpenResult(
            dav = response.headers["DAV"],
            allow = response.headers["Allow"],
        )
    }

    override suspend fun stat(
        path: String,
    ): WebDavResource? = propfindResource(
        path = validateObjectPath(path),
        operation = WebDavOperation.Stat,
        collection = false,
    )

    override suspend fun read(
        path: String,
        range: WebDavByteRange?,
    ): Source {
        val objectPath = validateObjectPath(path)
        var lastFailure: WebDavException? = null
        var useConditionalGet = true
        repeat(READ_ATTEMPTS) { attempt ->
            try {
                return readOnce(objectPath, range, useConditionalGet)
            } catch (e: WebDavException) {
                if (!e.isRetryableReadFailure() || attempt == READ_ATTEMPTS - 1) {
                    throw e
                }
                if (e.statusCode == STATUS_PRECONDITION_FAILED) {
                    // A fresh PROPFIND on the next attempt still protects the
                    // read on servers that mishandle If-Match on GET.
                    useConditionalGet = false
                }
                lastFailure = e
                delay(READ_RETRY_DELAY_MILLIS)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private suspend fun readOnce(
        objectPath: String,
        range: WebDavByteRange?,
        useConditionalGet: Boolean,
    ): Source {
        val metadata = propfindResource(
            path = objectPath,
            operation = WebDavOperation.Read,
            collection = false,
        ) ?: throw WebDavException.NotFound(
            operation = WebDavOperation.Read,
            path = objectPath,
        )
        if (metadata.isCollection) {
            throw WebDavException.NotFound(
                operation = WebDavOperation.Read,
                path = objectPath,
            )
        }

        return requestStreamingSource(
            operation = WebDavOperation.Read,
            path = objectPath,
            url = resolveWebDavUrl(baseUrl, objectPath),
            method = HttpMethod.Get,
            block = {
                header(HEADER_ACCEPT_ENCODING, "identity")
                if (useConditionalGet) {
                    metadata.etag
                        ?.takeIf { etag -> etag.isStrongEtag() }
                        ?.let { etag -> header(HttpHeaders.IfMatch, etag) }
                }
                if (range != null) {
                    header(HttpHeaders.Range, range.toHttpRangeHeader())
                }
            },
            transform = transform@{ response ->
                when {
                    range == null && response.status == HttpStatusCode.OK -> {
                        return@transform response.validatedBodySource(
                            path = objectPath,
                            expectedResourceSize = metadata.size,
                            expectedEtag = metadata.etag,
                            range = null,
                        )
                    }
                    range != null && response.status.value == STATUS_PARTIAL_CONTENT -> {
                        return@transform response.validatedBodySource(
                            path = objectPath,
                            expectedResourceSize = metadata.size,
                            expectedEtag = metadata.etag,
                            range = range,
                        )
                    }
                    range != null && response.status == HttpStatusCode.OK -> {
                        throw WebDavException.InvalidRange(
                            operation = WebDavOperation.Read,
                            path = objectPath,
                            statusCode = response.status.value,
                        )
                    }
                    response.status.value == STATUS_RANGE_NOT_SATISFIABLE -> {
                        throw WebDavException.InvalidRange(
                            operation = WebDavOperation.Read,
                            path = objectPath,
                            statusCode = response.status.value,
                        )
                    }
                    response.status.value == STATUS_PRECONDITION_FAILED -> {
                        throw WebDavException.Transient(
                            operation = WebDavOperation.Read,
                            path = objectPath,
                            statusCode = response.status.value,
                        )
                    }
                    else -> requireSuccessfulStatus(
                        response = response,
                        operation = WebDavOperation.Read,
                        path = objectPath,
                        success = false,
                    )
                }
                error("Unreachable.")
            },
        )
    }

    private suspend fun HttpResponse.validatedBodySource(
        path: String,
        expectedResourceSize: Long?,
        expectedEtag: String?,
        range: WebDavByteRange?,
    ): Source {
        val expectedBodySize = try {
            validateReadHeaders(
                path = path,
                expectedResourceSize = expectedResourceSize,
                expectedEtag = expectedEtag,
                range = range,
            )
        } catch (e: Exception) {
            bodyAsChannel().cancel(CancellationException("Rejected inconsistent WebDAV response"))
            throw e
        }
        val upstream = bodyAsWebDavSource(
            operation = WebDavOperation.Read,
            path = path,
        )
        return validatingBodySource(
            upstream = upstream,
            path = path,
            expectedSize = expectedBodySize,
        )
    }

    private fun HttpResponse.validateReadHeaders(
        path: String,
        expectedResourceSize: Long?,
        expectedEtag: String?,
        range: WebDavByteRange?,
    ): Long? {
        if (expectedResourceSize != null && expectedResourceSize < 0L) {
            throw inconsistentRead(
                path = path,
                message = "DAV:getcontentlength must not be negative",
            )
        }
        val contentEncoding = headers[HttpHeaders.ContentEncoding]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw inconsistentRead(
                path = path,
                message = "GET returned unsupported Content-Encoding $contentEncoding",
            )
        }

        val httpSizeHeader = headers[HttpHeaders.ContentLength]
        val httpSize = httpSizeHeader?.toLongOrNull()
        if (httpSizeHeader != null && (httpSize == null || httpSize < 0L)) {
            throw inconsistentRead(
                path = path,
                message = "GET returned invalid Content-Length",
            )
        }

        val responseEtag = headers[HttpHeaders.ETag]?.trim()?.takeIf { it.isNotEmpty() }
        if (expectedEtag != null && responseEtag != null && !etagsMatch(responseEtag, expectedEtag)) {
            throw inconsistentRead(
                path = path,
                message = "GET ETag did not match DAV:getetag",
            )
        }

        if (range == null) {
            if (httpSize != null && expectedResourceSize != null && httpSize != expectedResourceSize) {
                throw inconsistentRead(
                    path = path,
                    message = "GET Content-Length $httpSize did not match DAV:getcontentlength $expectedResourceSize",
                )
            }
            return httpSize ?: expectedResourceSize
        }

        val contentRange = headers[HttpHeaders.ContentRange]
            ?.parseContentRangeOrNull()
            ?: throw inconsistentRead(
                path = path,
                message = "partial GET returned an invalid or missing Content-Range",
            )
        if (contentRange.start != range.offset) {
            throw inconsistentRead(
                path = path,
                message = "partial GET started at ${contentRange.start} instead of ${range.offset}",
            )
        }
        val requestedEnd = range.endInclusiveOrNull()
        if (requestedEnd != null && contentRange.endInclusive > requestedEnd) {
            throw inconsistentRead(
                path = path,
                message = "partial GET ended past the requested byte range",
            )
        }
        val effectiveResourceSize = contentRange.resourceSize ?: expectedResourceSize
        if (contentRange.resourceSize != null && expectedResourceSize != null &&
            contentRange.resourceSize != expectedResourceSize
        ) {
            throw inconsistentRead(
                path = path,
                message = "GET Content-Range size did not match DAV:getcontentlength",
            )
        }
        if (effectiveResourceSize != null && range.offset >= effectiveResourceSize) {
            throw WebDavException.InvalidRange(
                operation = WebDavOperation.Read,
                path = path,
                statusCode = STATUS_PARTIAL_CONTENT,
            )
        }
        if (requestedEnd != null && effectiveResourceSize != null && requestedEnd >= effectiveResourceSize) {
            throw WebDavException.InvalidRange(
                operation = WebDavOperation.Read,
                path = path,
                statusCode = STATUS_PARTIAL_CONTENT,
            )
        }
        val expectedRangeEnd = requestedEnd ?: effectiveResourceSize?.minus(1L)
        if (expectedRangeEnd != null && contentRange.endInclusive != expectedRangeEnd) {
            throw inconsistentRead(
                path = path,
                message = "partial GET did not return the complete requested byte range",
            )
        }
        val rangeSizeMinusOne = contentRange.endInclusive - contentRange.start
        if (rangeSizeMinusOne == Long.MAX_VALUE) {
            throw inconsistentRead(
                path = path,
                message = "GET Content-Range length was too large",
            )
        }
        val rangeSize = rangeSizeMinusOne + 1L
        if (httpSize != null && httpSize != rangeSize) {
            throw inconsistentRead(
                path = path,
                message = "GET Content-Length $httpSize did not match Content-Range length $rangeSize",
            )
        }
        return rangeSize
    }

    private fun validatingBodySource(
        upstream: Source,
        path: String,
        expectedSize: Long?,
    ): Source {
        if (expectedSize == null) {
            return upstream
        }
        return object : RawSource {
            private val scratch = Buffer()
            private var delivered = 0L

            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (byteCount == 0L) {
                    return 0L
                }
                val remaining = expectedSize - delivered
                val validationLimit = if (remaining == Long.MAX_VALUE) {
                    Long.MAX_VALUE
                } else {
                    remaining + 1L
                }
                val amountToRead = minOf(
                    byteCount,
                    READ_VALIDATION_CHUNK_SIZE,
                    validationLimit,
                )
                val read = upstream.readAtMostTo(scratch, amountToRead)
                if (read == -1L) {
                    if (remaining != 0L) {
                        throw inconsistentRead(
                            path = path,
                            message = "GET ended after $delivered bytes; expected $expectedSize",
                        )
                    }
                    return -1L
                }
                if (read > remaining) {
                    throw inconsistentRead(
                        path = path,
                        message = "GET returned more than the expected $expectedSize bytes",
                    )
                }
                sink.write(scratch, read)
                delivered += read
                return read
            }

            override fun close() {
                upstream.close()
            }
        }.buffered()
    }

    private data class ContentRange(
        val start: Long,
        val endInclusive: Long,
        val resourceSize: Long?,
    )

    private fun String.parseContentRangeOrNull(): ContentRange? {
        val match = CONTENT_RANGE_REGEX.matchEntire(trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val endInclusive = match.groupValues[2].toLongOrNull() ?: return null
        val resourceSizeValue = match.groupValues[3]
        val resourceSize = if (resourceSizeValue == "*") {
            null
        } else {
            resourceSizeValue.toLongOrNull() ?: return null
        }
        if (start < 0L || endInclusive < start) {
            return null
        }
        if (resourceSize != null && (resourceSize <= 0L || endInclusive >= resourceSize)) {
            return null
        }
        return ContentRange(start, endInclusive, resourceSize)
    }

    private fun inconsistentRead(
        path: String,
        message: String,
    ) = WebDavException.Protocol(
        operation = WebDavOperation.Read,
        path = path,
        message = message,
        retryable = true,
    )

    private fun WebDavException.isRetryableReadFailure(): Boolean =
        retryable || this is WebDavException.Protocol || this is WebDavException.NotFound

    private fun String.isStrongEtag(): Boolean {
        val value = trim()
        return value.isNotEmpty() && !value.startsWith("W/", ignoreCase = true)
    }

    private fun String.asWeakEtagCompatibilityIfMatch(): String {
        val value = trim()
        return if (value.startsWith("W/", ignoreCase = true)) {
            value.substring(2)
        } else {
            value
        }
    }

    /**
     * Compares ETags with weak semantics, so a validator weakened in
     * transit (for example by a proxy) does not read as a change.
     * Client-side checks must never false-positive on the weak marker;
     * only enforcement requests sent to the server prefer the strong form.
     */
    private fun etagsMatch(
        actual: String?,
        expected: String,
    ): Boolean = actual != null &&
        actual.asWeakEtagCompatibilityIfMatch() == expected.asWeakEtagCompatibilityIfMatch()

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
        precondition: WebDavWritePrecondition?,
    ): WebDavResource = write(
        path = path,
        mode = mode,
        contentLength = bytes.size.toLong(),
        precondition = precondition,
    ) { sink ->
        sink.write(bytes)
        sink.flush()
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        write: suspend (Sink) -> Unit,
    ): WebDavResource {
        val objectPath = validateObjectPath(path)
        ensureParentCollections(objectPath)

        /*
         * The payload is uploaded to a temporary sibling, verified, and
         * swapped into place with MOVE, so an interrupted upload can never
         * tear the destination. Concurrency protection is layered: a
         * client-side ETag check runs before the upload and again right
         * before the swap, and the MOVE carries a destination ETag condition
         * for servers that enforce it.
         *
         * A 412 is disambiguated against a fresh destination stat. Replace
         * writes can degrade from a rejected ETag condition after that check,
         * while Create writes always retain their no-overwrite condition.
         * Servers without MOVE fail unless this client explicitly allows a
         * direct PUT fallback, which loses atomic replacement. Only a
         * server-enforced condition closes the race between the final check
         * and the swap; on servers that
         * ignore conditions a concurrent edit inside that window can still
         * be overwritten. The `write` callback may be invoked more than once
         * when the flow degrades, so it must produce the payload again.
         */
        ensureDestinationMatchesPrecondition(
            path = objectPath,
            mode = mode,
            precondition = precondition,
        )
        if (!moveUnsupported) {
            val published = publishViaTempMove(
                path = objectPath,
                mode = mode,
                contentLength = contentLength,
                precondition = precondition,
                write = write,
            )
            if (published) {
                return awaitWrittenResource(
                    path = objectPath,
                    expectedSize = contentLength,
                )
            }
        }
        if (writeStrategy == WebDavWriteStrategy.RequireAtomic) {
            throw WebDavException.AtomicWriteUnsupported(
                path = objectPath,
            )
        }
        writeViaDirectPut(
            path = objectPath,
            mode = mode,
            contentLength = contentLength,
            precondition = precondition,
            write = write,
        )
        return awaitWrittenResource(
            path = objectPath,
            expectedSize = contentLength,
        )
    }

    /**
     * Uploads to a temporary sibling and swaps it into place with MOVE.
     * Returns false when the server does not support MOVE and this client
     * allows a direct PUT fallback. Atomic clients fail instead.
     */
    private suspend fun publishViaTempMove(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        write: suspend (Sink) -> Unit,
    ): Boolean {
        val tempPath = createTempPath(path)
        try {
            put(
                path = tempPath,
                contentLength = contentLength,
                write = write,
            )
            // Catch a truncated or invisible upload before it can replace
            // the destination.
            awaitWrittenResource(
                path = tempPath,
                expectedSize = contentLength,
            )
            // Re-check right before the swap to shrink the window in which
            // a concurrent change can be overwritten on servers that ignore
            // the MOVE condition.
            ensureDestinationMatchesPrecondition(
                path = path,
                mode = mode,
                precondition = precondition,
            )
            moveIntoPlace(
                sourcePath = tempPath,
                destinationPath = path,
                mode = mode,
                precondition = precondition,
            )
            return true
        } catch (e: MoveNotSupportedException) {
            moveUnsupported = true
            runCatching {
                deleteObject(tempPath)
            }
            if (writeStrategy == WebDavWriteStrategy.RequireAtomic) {
                throw WebDavException.AtomicWriteUnsupported(
                    path = path,
                    statusCode = e.statusCode,
                    cause = e,
                )
            }
            return false
        } catch (e: Exception) {
            runCatching {
                deleteObject(tempPath)
            }
            throw e
        }
    }

    private suspend fun moveIntoPlace(
        sourcePath: String,
        destinationPath: String,
        mode: WebDavWriteMode,
        precondition: WebDavWritePrecondition?,
    ) {
        val overwrite = mode == WebDavWriteMode.CreateOrReplace
        val conditionEtag = if (overwrite && !conditionalMoveUnsupported) {
            precondition?.destinationEtag?.asWeakEtagCompatibilityIfMatch()
        } else {
            null
        }
        val response = moveOnce(
            sourcePath = sourcePath,
            destinationPath = destinationPath,
            overwrite = overwrite,
            conditionEtag = conditionEtag,
        )
        if (response.status.value != STATUS_PRECONDITION_FAILED) {
            requireSuccessfulMove(response, sourcePath)
            return
        }

        // Overwrite: F makes a Create 412 an authoritative collision result.
        // A later stat could race with another delete, so do not use it to
        // reinterpret the response or retry without the condition.
        if (mode == WebDavWriteMode.Create) {
            throw WebDavException.AlreadyExists(
                operation = WebDavOperation.Write,
                path = destinationPath,
                statusCode = STATUS_PRECONDITION_FAILED,
            )
        }

        // A replace 412 is ambiguous: a genuine concurrent change, or a
        // server that mishandles the destination ETag condition. A fresh
        // destination stat tells them apart.
        ensureDestinationMatchesPrecondition(
            path = destinationPath,
            mode = mode,
            precondition = precondition,
        )
        if (conditionEtag != null) {
            conditionalMoveUnsupported = true
        }
        val retryResponse = moveOnce(
            sourcePath = sourcePath,
            destinationPath = destinationPath,
            overwrite = true,
            conditionEtag = null,
        )
        if (retryResponse.status.value == STATUS_PRECONDITION_FAILED) {
            throw inconsistentWrite(
                path = destinationPath,
                message = "server rejected an unconditional MOVE with 412",
            )
        }
        requireSuccessfulMove(retryResponse, sourcePath)
    }

    private suspend fun moveOnce(
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        conditionEtag: String?,
    ): HttpResponse {
        val destinationUrl = resolveWebDavUrl(baseUrl, destinationPath)
        return request(
            operation = WebDavOperation.Move,
            path = sourcePath,
            url = resolveWebDavUrl(baseUrl, sourcePath),
            method = HTTP_METHOD_MOVE,
        ) {
            retry {
                noRetry()
            }
            header(HEADER_DESTINATION, destinationUrl)
            header(HEADER_OVERWRITE, if (overwrite) "T" else "F")
            if (conditionEtag != null) {
                header(HEADER_IF, "<$destinationUrl> ([$conditionEtag])")
            }
        }
    }

    private suspend fun requireSuccessfulMove(
        response: HttpResponse,
        sourcePath: String,
    ) {
        if (response.status.value == STATUS_METHOD_NOT_ALLOWED ||
            response.status.value == STATUS_NOT_IMPLEMENTED
        ) {
            throw MoveNotSupportedException(response.status.value)
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Move,
            path = sourcePath,
            success = response.status.value in MOVE_SUCCESS_STATUSES,
        )
    }

    private suspend fun writeViaDirectPut(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        write: suspend (Sink) -> Unit,
    ) {
        var conditionalHeaders = mode == WebDavWriteMode.Create ||
            !conditionalReplacePutUnsupported
        repeat(DIRECT_PUT_ATTEMPTS) {
            val preconditionFailed = putOnce(
                path = path,
                mode = mode,
                contentLength = contentLength,
                precondition = precondition,
                conditionalHeaders = conditionalHeaders,
                write = write,
            )
            if (!preconditionFailed) {
                return
            }
            // If-None-Match: * makes a Create 412 an authoritative collision
            // result. Never retry the PUT without that condition.
            if (mode == WebDavWriteMode.Create) {
                throw WebDavException.AlreadyExists(
                    operation = WebDavOperation.Write,
                    path = path,
                    statusCode = STATUS_PRECONDITION_FAILED,
                )
            }

            // Replace writes can degrade from a rejected ETag condition after
            // a fresh destination check.
            ensureDestinationMatchesPrecondition(
                path = path,
                mode = mode,
                precondition = precondition,
            )
            val sentConditions = conditionalHeaders && precondition != null
            if (!sentConditions) {
                throw inconsistentWrite(
                    path = path,
                    message = "server rejected an unconditional PUT with 412",
                )
            }
            conditionalReplacePutUnsupported = true
            conditionalHeaders = false
        }
        error("Unreachable.")
    }

    /**
     * Client-side precondition check against a fresh destination stat. Used
     * as the preflight before upload, the re-check before the swap, and the
     * disambiguation of a replace 412: it throws only on a genuine conflict.
     */
    private suspend fun ensureDestinationMatchesPrecondition(
        path: String,
        mode: WebDavWriteMode,
        precondition: WebDavWritePrecondition?,
    ) {
        if (mode == WebDavWriteMode.Create) {
            if (propfindResource(path, WebDavOperation.Stat, collection = false) != null) {
                throw WebDavException.AlreadyExists(
                    operation = WebDavOperation.Write,
                    path = path,
                    statusCode = STATUS_PRECONDITION_FAILED,
                )
            }
            return
        }
        val expectedEtag = precondition?.destinationEtag ?: return
        val resource = propfindResource(
            path = path,
            operation = WebDavOperation.Stat,
            collection = false,
        )
        if (!etagsMatch(resource?.etag, expectedEtag)) {
            throw WebDavException.PreconditionFailed(
                operation = WebDavOperation.Write,
                path = path,
                statusCode = STATUS_PRECONDITION_FAILED,
            )
        }
    }

    private fun inconsistentWrite(
        path: String,
        message: String,
        retryable: Boolean = true,
    ) = WebDavException.Protocol(
        operation = WebDavOperation.Write,
        path = path,
        message = message,
        retryable = retryable,
    )

    private suspend fun awaitWrittenResource(
        path: String,
        expectedSize: Long?,
    ): WebDavResource {
        var lastFailure: WebDavException? = null
        repeat(POST_WRITE_STAT_ATTEMPTS) { attempt ->
            try {
                val resource = propfindResource(
                    path = path,
                    operation = WebDavOperation.Write,
                    collection = false,
                )
                if (resource != null) {
                    if (expectedSize == null || resource.size == null || resource.size == expectedSize) {
                        return resource
                    }
                    lastFailure = WebDavException.Protocol(
                        operation = WebDavOperation.Write,
                        path = path,
                        message = "written resource size ${resource.size} did not match $expectedSize",
                        retryable = true,
                    )
                }
            } catch (e: WebDavException) {
                if (e !is WebDavException.Transient && e !is WebDavException.Protocol) {
                    throw e
                }
                lastFailure = e
            }
            if (attempt < POST_WRITE_STAT_ATTEMPTS - 1) {
                delay(POST_WRITE_STAT_RETRY_DELAY_MILLIS)
            }
        }
        throw lastFailure ?: WebDavException.Protocol(
            operation = WebDavOperation.Write,
            path = path,
            message = "written resource was not visible after PUT",
            retryable = true,
        )
    }

    override suspend fun list(
        prefix: String,
    ): List<WebDavResource> {
        val normalizedPrefix = validatePrefixPath(prefix)
        val startCollection = startCollectionForPrefix(normalizedPrefix)
        val startResource = propfindResource(
            path = startCollection,
            operation = WebDavOperation.List,
            collection = true,
        ) ?: return emptyList()
        if (!startResource.isCollection) {
            return emptyList()
        }

        val queue = ArrayDeque<String>()
        val result = mutableListOf<WebDavResource>()
        queue.add(startCollection)
        while (queue.isNotEmpty()) {
            val collectionPath = queue.removeFirst()
            propfindChildren(collectionPath).forEach { resource ->
                if (resource.path == collectionPath) {
                    return@forEach
                }
                if (resource.isCollection) {
                    queue.add(resource.path)
                } else if (resource.path.startsWith(normalizedPrefix)) {
                    result += resource
                }
            }
        }

        return result.sortedBy { it.path }
    }

    override suspend fun delete(
        path: String,
    ) {
        deleteObject(validateObjectPath(path))
    }

    override suspend fun close() {
        if (closeHttpClient) {
            httpClient.close()
        }
    }

    private suspend fun deleteObject(
        path: String,
    ) {
        val metadata = propfindResource(
            path = path,
            operation = WebDavOperation.Delete,
            collection = false,
        ) ?: return
        if (metadata.isCollection) {
            return
        }

        val response = request(
            operation = WebDavOperation.Delete,
            path = path,
            url = resolveWebDavUrl(baseUrl, path),
            method = HttpMethod.Delete,
        )
        if (response.status.value == STATUS_NOT_FOUND) {
            return
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Delete,
            path = path,
            success = response.status.value in DELETE_SUCCESS_STATUSES,
        )
    }

    private suspend fun put(
        path: String,
        contentLength: Long?,
        write: suspend (Sink) -> Unit,
    ) {
        val preconditionFailed = putOnce(
            path = path,
            mode = WebDavWriteMode.CreateOrReplace,
            contentLength = contentLength,
            precondition = null,
            conditionalHeaders = false,
            write = write,
        )
        if (preconditionFailed) {
            throw inconsistentWrite(
                path = path,
                message = "server rejected an unconditional PUT with 412",
            )
        }
    }

    /**
     * Returns true when the server answered 412; the caller owns the
     * disambiguation between a genuine conflict and a condition quirk.
     */
    private suspend fun putOnce(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        conditionalHeaders: Boolean,
        write: suspend (Sink) -> Unit,
    ): Boolean {
        val response = request(
            operation = WebDavOperation.Write,
            path = path,
            url = resolveWebDavUrl(baseUrl, path),
            method = HttpMethod.Put,
        ) {
            val expectedContentLength = contentLength
            retry {
                noRetry()
            }
            if (conditionalHeaders) {
                when (mode) {
                    WebDavWriteMode.Create -> header(HttpHeaders.IfNoneMatch, "*")
                    WebDavWriteMode.CreateOrReplace -> precondition?.let { condition ->
                        header(
                            HttpHeaders.IfMatch,
                            condition.destinationEtag.asWeakEtagCompatibilityIfMatch(),
                        )
                    }
                }
            }
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = expectedContentLength
                    override val contentType: ContentType = ContentType.Application.OctetStream

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeWebDavBody(write)
                    }
                },
            )
        }
        if (response.status.value == STATUS_PRECONDITION_FAILED) {
            return true
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Write,
            path = path,
            success = response.status.value in PUT_SUCCESS_STATUSES,
        )
        return false
    }

    private suspend fun ByteWriteChannel.writeWebDavBody(
        write: suspend (Sink) -> Unit,
    ) {
        val sink = asSink().buffered()
        write(sink)
        sink.flush()
    }

    private suspend fun HttpResponse.bodyAsWebDavSource(
        operation: WebDavOperation,
        path: String,
    ): Source {
        val upstream = try {
            bodyAsChannel().asSource()
        } catch (e: WebDavException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WebDavException.Transient(
                operation = operation,
                path = path,
                cause = e,
            )
        }
        return object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = mapStreamingException(operation, path) {
                upstream.readAtMostTo(sink, byteCount)
            }

            override fun close() {
                mapStreamingException(operation, path) {
                    upstream.close()
                }
            }
        }.buffered()
    }

    private inline fun <T> mapStreamingException(
        operation: WebDavOperation,
        path: String?,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: WebDavException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WebDavException.Transient(
            operation = operation,
            path = path,
            cause = e,
        )
    }

    private fun Throwable.cancellationCauseOrNull(): CancellationException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is CancellationException) {
                return current
            }
            val next = current.cause
            if (next === current) {
                break
            }
            current = next
        }
        return null
    }

    private suspend fun ensureParentCollections(
        path: String,
    ) {
        val parts = path.split('/').dropLast(1)
        var current = ""
        parts.forEach { part ->
            current = if (current.isEmpty()) part else "$current/$part"
            val resource = propfindResource(
                path = current,
                operation = WebDavOperation.Mkcol,
                collection = true,
            )
            when {
                resource == null -> mkcol(current)
                !resource.isCollection -> throw WebDavException.AlreadyExists(
                    operation = WebDavOperation.Write,
                    path = current,
                )
            }
        }
    }

    private suspend fun mkcol(
        path: String,
    ) {
        val response = request(
            operation = WebDavOperation.Mkcol,
            path = path,
            url = resolveWebDavUrl(baseUrl, path, collection = true),
            method = HTTP_METHOD_MKCOL,
        )
        if (response.status.value == STATUS_METHOD_NOT_ALLOWED) {
            val existing = propfindResource(
                path = path,
                operation = WebDavOperation.Mkcol,
                collection = true,
            )
            if (existing?.isCollection == true) {
                return
            }
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Mkcol,
            path = path,
            success = response.status.value == STATUS_CREATED,
        )
    }

    private suspend fun propfindResource(
        path: String,
        operation: WebDavOperation,
        collection: Boolean,
    ): WebDavResource? {
        val response = propfind(
            path = path,
            operation = operation,
            depth = 0,
            collection = collection,
        )
        if (response.status.value == STATUS_NOT_FOUND) {
            return null
        }
        requireSuccessfulStatus(
            response = response,
            operation = operation,
            path = path.ifEmpty { null },
            success = response.status.value == STATUS_MULTI_STATUS,
        )

        val entries = response
            .bodyAsWebDavText(operation, path)
            .toMultiStatus(operation, path)
        val entry = entries.findEntry(path) ?: return null
        return entry.toResource(
            path = path,
            operation = operation,
        )
    }

    private suspend fun propfindChildren(
        path: String,
    ): List<WebDavResource> {
        val response = propfind(
            path = path,
            operation = WebDavOperation.List,
            depth = 1,
            collection = true,
        )
        if (response.status.value == STATUS_NOT_FOUND) {
            return emptyList()
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.List,
            path = path.ifEmpty { null },
            success = response.status.value == STATUS_MULTI_STATUS,
        )

        return response
            .bodyAsWebDavText(WebDavOperation.List, path)
            .toMultiStatus(WebDavOperation.List, path)
            .mapNotNull { entry ->
                val entryPath = hrefToWebDavPath(baseUrl, entry.href) ?: return@mapNotNull null
                entry.toResource(
                    path = entryPath,
                    operation = WebDavOperation.List,
                )
            }
    }

    private suspend fun propfind(
        path: String,
        operation: WebDavOperation,
        depth: Int,
        collection: Boolean,
    ): HttpResponse = request(
        operation = operation,
        path = path.ifEmpty { null },
        url = resolveWebDavUrl(baseUrl, path, collection = collection),
        method = HTTP_METHOD_PROPFIND,
    ) {
        header(HEADER_DEPTH, depth.toString())
        header(HttpHeaders.ContentType, XML_CONTENT_TYPE)
        setBody(WebDavXml.propfindBody())
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun requestStreamingSource(
        operation: WebDavOperation,
        path: String,
        url: String,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit,
        transform: suspend (HttpResponse) -> Source,
    ): Source {
        val result = CompletableDeferred<Source>()
        val callerJob = currentCoroutineContext()[Job]
        var cancellationHandle: DisposableHandle? = null
        val requestJob = httpClient.launch {
            try {
                // HttpClient.request() saves the complete response body before
                // returning. Keep execute() active until the Source reaches EOF
                // or closes so GET remains a true streaming operation.
                httpClient.prepareRequest(url) {
                    this.method = method
                    applyCommonHeaders()
                    block()
                }.execute { response ->
                    val finished = CompletableDeferred<Unit>()
                    val source = transform(response).completeWith(
                        finished = finished,
                        callerJob = callerJob,
                        operation = operation,
                        path = path,
                    )
                    if (!result.complete(source)) {
                        source.close()
                        return@execute
                    }
                    finished.await()
                }
            } catch (e: WebDavException) {
                result.completeExceptionally(e)
            } catch (e: CancellationException) {
                result.completeExceptionally(e)
            } catch (e: Exception) {
                result.completeExceptionally(
                    WebDavException.Transient(
                        operation = operation,
                        path = path,
                        cause = e,
                    ),
                )
            }
        }
        cancellationHandle = callerJob?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause is CancellationException) {
                requestJob.cancel(cause)
            }
        }
        requestJob.invokeOnCompletion {
            cancellationHandle?.dispose()
        }
        return try {
            result.await()
        } catch (e: CancellationException) {
            requestJob.cancel(e)
            throw e
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun Source.completeWith(
        finished: CompletableDeferred<Unit>,
        callerJob: Job?,
        operation: WebDavOperation,
        path: String,
    ): Source = object : RawSource {
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = try {
            this@completeWith.readAtMostTo(sink, byteCount).also { read ->
                if (read == -1L) {
                    finished.complete(Unit)
                }
            }
        } catch (e: Exception) {
            finished.complete(Unit)
            if (callerJob?.isCancelled == true) {
                throw callerJob.getCancellationException()
            }
            if (e !is WebDavException &&
                (e is CancellationException || e.cancellationCauseOrNull() != null)
            ) {
                // The request coroutine was torn down while the caller is
                // still active: this is a transport failure of the stream,
                // not a cancellation of the caller.
                throw WebDavException.Transient(
                    operation = operation,
                    path = path,
                    cause = e,
                )
            }
            throw e
        }

        override fun close() {
            try {
                this@completeWith.close()
            } finally {
                finished.complete(Unit)
            }
        }
    }.buffered()

    private suspend fun request(
        operation: WebDavOperation,
        path: String?,
        url: String,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = try {
        httpClient.request(url) {
            this.method = method
            applyCommonHeaders()
            block()
        }
    } catch (e: WebDavException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WebDavException.Transient(
            operation = operation,
            path = path,
            cause = e,
        )
    }

    private fun HttpRequestBuilder.applyCommonHeaders() {
        if (noCache) {
            header(HttpHeaders.CacheControl, "no-cache, no-store")
        }
        userAgent?.let { value ->
            header(HttpHeaders.UserAgent, value)
        }
        when (val auth = authorization) {
            is WebDavAuthorization.Basic -> {
                val token = Base64.Default.encode("${auth.username}:${auth.password}".encodeToByteArray())
                header(HttpHeaders.Authorization, "Basic $token")
            }
            is WebDavAuthorization.Bearer -> {
                header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            }
            is WebDavAuthorization.Header -> {
                header(HttpHeaders.Authorization, auth.value)
            }
            null -> Unit
        }
    }

    private suspend fun HttpResponse.bodyAsWebDavText(
        operation: WebDavOperation,
        path: String,
    ): String = try {
        bodyAsText()
    } catch (e: WebDavException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WebDavException.Transient(
            operation = operation,
            path = path.ifEmpty { null },
            cause = e,
        )
    }

    private fun String.toMultiStatus(
        operation: WebDavOperation,
        path: String,
    ): List<WebDavMultiStatusEntry> = try {
        WebDavXml.parseMultiStatus(this)
    } catch (e: Exception) {
        throw WebDavException.Protocol(
            operation = operation,
            path = path.ifEmpty { null },
            message = "could not parse DAV:multistatus",
            cause = e,
            retryable = operation == WebDavOperation.Read || operation == WebDavOperation.Write,
        )
    }

    private fun List<WebDavMultiStatusEntry>.findEntry(
        requestedPath: String,
    ): WebDavMultiStatusEntry? {
        val normalizedRequestedPath = requestedPath.trim('/')
        return firstOrNull { entry ->
            hrefToWebDavPath(baseUrl, entry.href) == normalizedRequestedPath
        } ?: singleOrNull()
    }

    private fun WebDavMultiStatusEntry.toResource(
        path: String,
        operation: WebDavOperation,
    ): WebDavResource? {
        val targetStatusCode = statusCode
        if (targetStatusCode == STATUS_NOT_FOUND) {
            return null
        }
        if (targetStatusCode != null && targetStatusCode !in 200..299) {
            throw mapStatus(
                operation = operation,
                path = path.ifEmpty { null },
                statusCode = targetStatusCode,
            )
        }

        val properties = propStats
            .filter { propStat -> propStat.statusCode == null || propStat.statusCode in 200..299 }
            .flatMap { propStat -> propStat.properties.entries }
            .associate { (key, value) -> key to value }
        val resourceType = properties[WebDavXml.RESOURCETYPE]
        val isCollection = resourceType
            ?.children
            ?.any { child -> child.name == WebDavXml.COLLECTION } == true ||
                href.endsWith("/")

        return WebDavResource(
            path = path.trim('/'),
            isCollection = isCollection,
            size = properties[WebDavXml.GET_CONTENT_LENGTH]
                ?.directTextContent
                ?.trim()
                ?.toLongOrNull(),
            lastModified = properties[WebDavXml.GET_LAST_MODIFIED]
                ?.directTextContent
                ?.trim()
                ?.let(::parseHttpDateOrNull),
            etag = properties[WebDavXml.GET_ETAG]
                ?.directTextContent
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun requireSuccessfulStatus(
        response: HttpResponse,
        operation: WebDavOperation,
        path: String?,
        success: Boolean,
    ) {
        if (success) {
            return
        }
        throw mapStatus(
            operation = operation,
            path = path,
            statusCode = response.status.value,
        )
    }

    private fun mapStatus(
        operation: WebDavOperation,
        path: String?,
        statusCode: Int,
    ): WebDavException = when (statusCode) {
        STATUS_UNAUTHORIZED -> WebDavException.AuthenticationFailed(
            operation = operation,
            statusCode = statusCode,
        )
        STATUS_NOT_FOUND -> WebDavException.NotFound(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
        STATUS_PRECONDITION_FAILED -> WebDavException.AlreadyExists(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
        STATUS_RANGE_NOT_SATISFIABLE -> WebDavException.InvalidRange(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
        STATUS_INSUFFICIENT_STORAGE -> WebDavException.InsufficientStorage(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
        STATUS_TOO_MANY_REQUESTS, in 500..599 -> WebDavException.Transient(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
        else -> WebDavException.PermissionDenied(
            operation = operation,
            path = path,
            statusCode = statusCode,
        )
    }

    private fun WebDavByteRange.toHttpRangeHeader(): String {
        val end = endInclusiveOrNull()
        return if (end != null) {
            "bytes=$offset-$end"
        } else {
            "bytes=$offset-"
        }
    }

    private fun WebDavByteRange.endInclusiveOrNull(): Long? = length?.let { length ->
        require(length - 1L <= Long.MAX_VALUE - offset) {
            "WebDAV read range must not overflow."
        }
        offset + length - 1L
    }

    private fun startCollectionForPrefix(
        prefix: String,
    ): String = when {
        prefix.isEmpty() -> ""
        prefix.endsWith("/") -> prefix.trimEnd('/')
        '/' in prefix -> prefix.substringBeforeLast('/')
        else -> ""
    }

    private fun createTempPath(
        path: String,
    ): String {
        val slashIndex = path.lastIndexOf('/')
        val parent = path.takeIf { slashIndex >= 0 }?.substring(0, slashIndex + 1).orEmpty()
        val fileName = path.substringAfterLast('/')
        val nonce = Random.Default.nextLong().toString().replace("-", "n")
        return "$parent$fileName.$nonce.tmp"
    }

    private companion object {
        private val HTTP_METHOD_PROPFIND = HttpMethod("PROPFIND")
        private val HTTP_METHOD_MKCOL = HttpMethod("MKCOL")
        private val HTTP_METHOD_MOVE = HttpMethod("MOVE")

        private const val HEADER_DEPTH = "Depth"
        private const val HEADER_DESTINATION = "Destination"
        private const val HEADER_OVERWRITE = "Overwrite"
        private const val HEADER_IF = "If"
        private const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
        private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"

        private const val STATUS_CREATED = 201
        private const val STATUS_PARTIAL_CONTENT = 206
        private const val STATUS_MULTI_STATUS = 207
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_PRECONDITION_FAILED = 412
        private const val STATUS_RANGE_NOT_SATISFIABLE = 416
        private const val STATUS_TOO_MANY_REQUESTS = 429
        private const val STATUS_NOT_IMPLEMENTED = 501
        private const val STATUS_INSUFFICIENT_STORAGE = 507

        private val PUT_SUCCESS_STATUSES = setOf(200, 201, 204)
        private val MOVE_SUCCESS_STATUSES = setOf(201, 204)
        private val DELETE_SUCCESS_STATUSES = setOf(200, 202, 204)

        private const val READ_ATTEMPTS = 2
        private const val READ_RETRY_DELAY_MILLIS = 350L
        private const val READ_VALIDATION_CHUNK_SIZE = 8_192L
        private const val POST_WRITE_STAT_ATTEMPTS = 3
        private const val POST_WRITE_STAT_RETRY_DELAY_MILLIS = 350L
        private const val DIRECT_PUT_ATTEMPTS = 2

        private val CONTENT_RANGE_REGEX = Regex(
            pattern = "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
            option = RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Internal control-flow signal: the server answered MOVE with 405 or 501,
 * so the write falls back to the direct PUT flow.
 */
private class MoveNotSupportedException(
    val statusCode: Int,
) : Exception("MOVE is not supported by the server.")
