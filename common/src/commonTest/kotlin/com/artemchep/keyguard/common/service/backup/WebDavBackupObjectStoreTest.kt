package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.readByteArrayAndClose
import com.artemchep.keyguard.common.io.toSource
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOpenResult
import com.artemchep.keyguard.util.webdav.WebDavOperation
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write

class WebDavBackupObjectStoreTest {
    @Test
    fun `write streams sink and delegates create mode`() = runTest {
        val client = FakeWebDavClient()
        val store = WebDavBackupObjectStore(client)
        val bytes = "payload".encodeToByteArray()

        val info = store.write(
            key = BackupObjectKey("snapshots/one.zip"),
            mode = BackupWriteMode.Create,
        ) { sink ->
            sink.write(bytes)
        }

        assertEquals("snapshots/one.zip", client.writtenPath)
        assertEquals(WebDavWriteMode.Create, client.writtenMode)
        assertEquals(null, client.writtenContentLength)
        assertContentEquals(bytes, client.writtenBytes)
        assertEquals(BackupObjectKey("snapshots/one.zip"), info.key)
        assertEquals(bytes.size.toLong(), info.size)
    }

    @Test
    fun `read delegates range and exposes source`() = runTest {
        val client = FakeWebDavClient(
            readBytes = "zabc".encodeToByteArray(),
        )
        val store = WebDavBackupObjectStore(client)
        val range = BackupByteRange(
            offset = 1L,
            length = 3L,
        )

        val bytes = store
            .read(
                key = BackupObjectKey("object.zip"),
                range = range,
            )
            .readByteArrayAndClose()

        assertContentEquals("abc".encodeToByteArray(), bytes)
        assertEquals(WebDavByteRange(1L, 3L), client.readRange)
    }

    @Test
    fun `list maps resources to sorted backup object infos`() = runTest {
        val client = FakeWebDavClient(
            listResources = listOf(
                resource("snapshots/two.zip", size = 2L),
                resource("snapshots/folder", isCollection = true),
                resource("snapshots/one.zip", size = 1L),
            ),
        )
        val store = WebDavBackupObjectStore(client)

        val page = store.list(BackupObjectKeyPrefix("snapshots/"))

        assertEquals(
            listOf("snapshots/one.zip", "snapshots/two.zip"),
            page.items.map { item -> item.key.value },
        )
        assertEquals(listOf(1L, 2L), page.items.map { item -> item.size })
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `test validates backend and cleans up probe`() = runTest {
        val client = FakeWebDavClient()
        val store = WebDavBackupObjectStore(client)

        val result = store.test()

        assertEquals(true, result.probeKey.value.startsWith("health-check/"))
        assertEquals(result.bytesWritten, result.bytesRead)
        assertEquals(true, result.listed)
        assertEquals(true, result.deleted)
        assertEquals(true, result.rangeRead)
        assertEquals(store.capabilities, result.capabilities)
        assertEquals(emptyMap(), client.objects)
    }

    @Test
    fun `web dav authorization maps basic credentials from store config`() {
        val store = BackupStoreConfig.WebDav(
            url = "https://example.com/dav/",
            username = " alice ",
            password = Password("secret"),
        )

        assertEquals(
            WebDavAuthorization.Basic(
                username = "alice",
                password = "secret",
            ),
            store.toWebDavAuthorization(),
        )
    }

    @Test
    fun `web dav authorization is absent without username`() {
        val store = BackupStoreConfig.WebDav(
            url = "https://example.com/dav/",
            username = " ",
            password = Password("secret"),
        )

        assertEquals(null, store.toWebDavAuthorization())
    }

    @Test
    fun `exception mapping preserves store semantics`() = runTest {
        val key = BackupObjectKey("object.zip")
        assertFailsWith<BackupObjectStoreException.NotFound> {
            WebDavBackupObjectStore(
                FakeWebDavClient(
                    readError = WebDavException.NotFound(
                        operation = WebDavOperation.Read,
                        path = key.value,
                    ),
                ),
            ).read(key).readByteArrayAndClose()
        }
        assertFailsWith<BackupObjectStoreException.InvalidRange> {
            WebDavBackupObjectStore(
                FakeWebDavClient(
                    readError = WebDavException.InvalidRange(
                        operation = WebDavOperation.Read,
                        path = key.value,
                    ),
                ),
            ).read(
                key = key,
                range = BackupByteRange(10L, 1L),
            ).readByteArrayAndClose()
        }
        assertFailsWith<BackupObjectStoreException.AuthenticationFailed> {
            WebDavBackupObjectStore(
                FakeWebDavClient(
                    readError = WebDavException.AuthenticationFailed(
                        operation = WebDavOperation.Read,
                    ),
                ),
            ).read(key).readByteArrayAndClose()
        }
        assertFailsWith<BackupObjectStoreException.Transient> {
            WebDavBackupObjectStore(
                FakeWebDavClient(
                    readError = WebDavException.Transient(
                        operation = WebDavOperation.Read,
                        path = key.value,
                    ),
                ),
            ).read(key).readByteArrayAndClose()
        }
    }

    @Test
    fun `streaming read errors are mapped to backup store errors`() = runTest {
        val key = BackupObjectKey("object.zip")

        assertFailsWith<BackupObjectStoreException.Transient> {
            WebDavBackupObjectStore(
                FakeWebDavClient(
                    readSourceError = WebDavException.Transient(
                        operation = WebDavOperation.Read,
                        path = key.value,
                    ),
                ),
            ).read(key).readByteArrayAndClose()
        }
    }
}

private class FakeWebDavClient(
    private val readBytes: ByteArray? = null,
    private val listResources: List<WebDavResource>? = null,
    private val readError: WebDavException? = null,
    private val readSourceError: WebDavException? = null,
) : WebDavClient {
    val objects = mutableMapOf<String, ByteArray>()
    var writtenPath: String? = null
    var writtenMode: WebDavWriteMode? = null
    var writtenContentLength: Long? = null
    var writtenBytes: ByteArray? = null
    var readRange: WebDavByteRange? = null
    var closeCalls: Int = 0

    override suspend fun open(): WebDavOpenResult = WebDavOpenResult(
        dav = "1",
        allow = null,
    )

    override suspend fun stat(
        path: String,
    ): WebDavResource? = objects[path]?.let { bytes ->
        resource(
            path = path,
            size = bytes.size.toLong(),
        )
    }

    override suspend fun read(
        path: String,
        range: WebDavByteRange?,
    ): Source {
        readError?.let { throw it }
        readRange = range
        readSourceError?.let { return failingSource(it) }
        val bytes = readBytes
            ?: objects[path]
            ?: throw WebDavException.NotFound(
                operation = WebDavOperation.Read,
                path = path,
            )
        return (range
            ?.let { bytes.sliceRange(it) }
            ?: bytes)
            .toSource()
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
    ): WebDavResource {
        writtenContentLength = bytes.size.toLong()
        return writeBytes(
            path = path,
            mode = mode,
            bytes = bytes,
        )
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        write: suspend (Sink) -> Unit,
    ): WebDavResource {
        val buffer = Buffer()
        write(buffer)
        writtenContentLength = contentLength
        return writeBytes(
            path = path,
            mode = mode,
            bytes = buffer.readByteArray(),
        )
    }

    private fun writeBytes(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
    ): WebDavResource {
        writtenPath = path
        writtenMode = mode
        writtenBytes = bytes
        objects[path] = bytes
        return resource(
            path = path,
            size = bytes.size.toLong(),
        )
    }

    override suspend fun list(
        prefix: String,
    ): List<WebDavResource> = listResources
        ?: objects
            .filterKeys { key -> key.startsWith(prefix) }
            .map { (path, bytes) ->
                resource(
                    path = path,
                    size = bytes.size.toLong(),
                )
            }

    override suspend fun delete(
        path: String,
    ) {
        objects.remove(path)
    }

    override suspend fun close() {
        closeCalls += 1
    }

    private fun ByteArray.sliceRange(
        range: WebDavByteRange,
    ): ByteArray {
        if (range.offset >= size) {
            throw WebDavException.InvalidRange(
                operation = WebDavOperation.Read,
                path = null,
            )
        }
        val startIndex = range.offset.toInt()
        val endIndex = range.length
            ?.let { length -> startIndex + length.toInt() }
            ?.coerceAtMost(size)
            ?: size
        return copyOfRange(startIndex, endIndex)
    }
}

private fun failingSource(
    error: WebDavException,
): Source = object : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        throw error
    }

    override fun close() {
        // no-op
    }
}.buffered()

private fun resource(
    path: String,
    size: Long? = null,
    isCollection: Boolean = false,
): WebDavResource = WebDavResource(
    path = path,
    isCollection = isCollection,
    size = size,
    lastModified = Instant.fromEpochMilliseconds(0L),
    etag = null,
)
