package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.util.foundation.io.toSource
import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOpenResult
import com.artemchep.keyguard.util.webdav.WebDavOperation
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import com.artemchep.keyguard.util.webdav.WebDavWritePrecondition
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

internal class FakeKeePassWebDavClientFactory : WebDavClientFactory {
    val client = FakeKeePassWebDavClient()
    val configs = mutableListOf<WebDavClientConfig>()

    override fun create(
        config: WebDavClientConfig,
    ): WebDavClient {
        configs += config
        return client
    }
}

internal class FakeKeePassWebDavClient : WebDavClient {
    private val objects = mutableMapOf<String, RemoteObject>()
    private val collections = mutableSetOf<String>()
    var writeCount: Int = 0
        private set
    var forceNonComparableMetadata: Boolean = false
    var writeError: Throwable? = null
    var lastWritePrecondition: WebDavWritePrecondition? = null
        private set
    var beforeWrite: suspend (String, WebDavWritePrecondition?) -> Unit = { _, _ -> }

    override suspend fun open(): WebDavOpenResult = WebDavOpenResult(
        dav = "1",
        allow = null,
    )

    override suspend fun stat(
        path: String,
    ): WebDavResource? = (
        objects[path]?.toResource(path)
        ?: path
            .takeIf(collections::contains)
            ?.let { collectionPath ->
                WebDavResource(
                    path = collectionPath,
                    isCollection = true,
                    size = null,
                    lastModified = Clock.System.now(),
                    etag = null,
                )
            }
    )?.let { resource ->
        if (forceNonComparableMetadata && !resource.isCollection) {
            resource.copy(
                size = null,
                lastModified = null,
                etag = null,
            )
        } else {
            resource
        }
    }

    override suspend fun read(
        path: String,
        range: WebDavByteRange?,
    ): Source {
        val bytes = objects[path]?.bytes
            ?: throw WebDavException.NotFound(
                operation = WebDavOperation.Read,
                path = path,
            )
        return bytes.toSource()
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
        precondition: WebDavWritePrecondition?,
    ): WebDavResource {
        writeError?.let { throw it }
        lastWritePrecondition = precondition
        beforeWrite(path, precondition)
        checkWritePrecondition(path, precondition)
        if (mode == WebDavWriteMode.Create && objects.containsKey(path)) {
            throw WebDavException.AlreadyExists(
                operation = WebDavOperation.Write,
                path = path,
            )
        }
        putObject(path, bytes)
        writeCount++
        return requireNotNull(stat(path))
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        write: suspend (Sink) -> Unit,
    ): WebDavResource {
        writeError?.let { throw it }
        lastWritePrecondition = precondition
        beforeWrite(path, precondition)
        checkWritePrecondition(path, precondition)
        if (mode == WebDavWriteMode.Create && objects.containsKey(path)) {
            throw WebDavException.AlreadyExists(
                operation = WebDavOperation.Write,
                path = path,
            )
        }
        val buffer = Buffer()
        write(buffer)
        putObject(path, buffer.readByteArray())
        writeCount++
        return requireNotNull(stat(path))
    }

    override suspend fun list(
        prefix: String,
    ): List<WebDavResource> = objects
        .filterKeys { key -> key.startsWith(prefix) }
        .map { (path, obj) -> obj.toResource(path) }

    override suspend fun delete(
        path: String,
    ) {
        objects.remove(path)
    }

    fun putObject(
        path: String,
        bytes: ByteArray,
    ) {
        val version = (objects[path]?.version ?: 0) + 1
        objects[path] = RemoteObject(
            bytes = bytes,
            version = version,
            lastModified = Clock.System.now(),
        )
    }

    fun putCollection(
        path: String,
    ) {
        collections += path
    }

    fun readObjectBytes(
        path: String,
    ): ByteArray = requireNotNull(objects[path]) {
        "Expected fake WebDAV object '$path' to exist."
    }.bytes

    private fun checkWritePrecondition(
        path: String,
        precondition: WebDavWritePrecondition?,
    ) {
        precondition ?: return
        val currentEtag = objects[path]?.toResource(path)?.etag
        if (currentEtag != precondition.destinationEtag) {
            throw WebDavException.PreconditionFailed(
                operation = WebDavOperation.Write,
                path = path,
            )
        }
    }

    private data class RemoteObject(
        val bytes: ByteArray,
        val version: Int,
        val lastModified: Instant,
    ) {
        fun toResource(
            path: String,
        ): WebDavResource = WebDavResource(
            path = path,
            isCollection = false,
            size = bytes.size.toLong(),
            lastModified = lastModified,
            etag = "\"$version\"",
        )
    }
}
