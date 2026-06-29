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
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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

        val response = request(
            operation = WebDavOperation.Read,
            path = objectPath,
            url = resolveWebDavUrl(baseUrl, objectPath),
            method = HttpMethod.Get,
        ) {
            if (range != null) {
                header(HttpHeaders.Range, range.toHttpRangeHeader())
                header(HEADER_ACCEPT_ENCODING, "identity")
            }
        }
        when {
            range == null && response.status == HttpStatusCode.OK -> {
                return response.bodyAsWebDavSource(
                    operation = WebDavOperation.Read,
                    path = objectPath,
                )
            }
            range != null && response.status.value == STATUS_PARTIAL_CONTENT -> {
                return response.bodyAsWebDavSource(
                    operation = WebDavOperation.Read,
                    path = objectPath,
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
            else -> requireSuccessfulStatus(
                response = response,
                operation = WebDavOperation.Read,
                path = objectPath,
                success = false,
            )
        }
        error("Unreachable.")
    }

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

        val tempPath = createTempPath(objectPath)
        try {
            put(
                path = tempPath,
                contentLength = contentLength,
                write = write,
            )
            move(
                sourcePath = tempPath,
                destinationPath = objectPath,
                overwrite = mode == WebDavWriteMode.CreateOrReplace,
                precondition = precondition,
            )
            return propfindResource(
                path = objectPath,
                operation = WebDavOperation.Write,
                collection = false,
            ) ?: throw WebDavException.Protocol(
                operation = WebDavOperation.Write,
                path = objectPath,
                message = "written resource was not visible after MOVE",
            )
        } catch (e: Exception) {
            runCatching {
                deleteObject(tempPath)
            }
            throw e
        }
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
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Write,
            path = path,
            success = response.status.value in PUT_SUCCESS_STATUSES,
        )
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

    private suspend fun move(
        sourcePath: String,
        destinationPath: String,
        overwrite: Boolean,
        precondition: WebDavWritePrecondition?,
    ) {
        val destinationUrl = resolveWebDavUrl(baseUrl, destinationPath)
        val response = request(
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
            if (precondition != null) {
                header(HEADER_IF, "<$destinationUrl> ([${precondition.destinationEtag}])")
            }
        }
        if (!overwrite && response.status.value == STATUS_PRECONDITION_FAILED) {
            throw WebDavException.AlreadyExists(
                operation = WebDavOperation.Write,
                path = destinationPath,
                statusCode = response.status.value,
            )
        }
        if (precondition != null && response.status.value == STATUS_PRECONDITION_FAILED) {
            throw WebDavException.PreconditionFailed(
                operation = WebDavOperation.Write,
                path = destinationPath,
                statusCode = response.status.value,
            )
        }
        requireSuccessfulStatus(
            response = response,
            operation = WebDavOperation.Move,
            path = sourcePath,
            success = response.status.value in MOVE_SUCCESS_STATUSES,
        )
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

        val entries = response.bodyAsText().toMultiStatus(operation, path)
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
            .bodyAsText()
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
        val end = length?.let { offset + it - 1L }
        return if (end != null) {
            "bytes=$offset-$end"
        } else {
            "bytes=$offset-"
        }
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
        private const val STATUS_INSUFFICIENT_STORAGE = 507

        private val PUT_SUCCESS_STATUSES = setOf(200, 201, 204)
        private val MOVE_SUCCESS_STATUSES = setOf(201, 204)
        private val DELETE_SUCCESS_STATUSES = setOf(200, 202, 204)
    }
}
