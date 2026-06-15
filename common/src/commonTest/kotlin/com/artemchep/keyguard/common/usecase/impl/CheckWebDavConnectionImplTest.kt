package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toSource
import com.artemchep.keyguard.common.usecase.CheckWebDavConnectionRequest
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavOpenResult
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source

class CheckWebDavConnectionImplTest {
    @Test
    fun `checks web dav connection with basic authorization`() = runTest {
        val client = FakeWebDavClient()
        var capturedConfig: WebDavClientConfig? = null
        val useCase = CheckWebDavConnectionImpl { config ->
            capturedConfig = config
            client
        }

        useCase(
            CheckWebDavConnectionRequest(
                url = "https://example.com/dav/",
                username = " alice ",
                password = "secret",
            ),
        ).bind()

        assertEquals(
            WebDavClientConfig(
                baseUrl = "https://example.com/dav/",
                authorization = WebDavAuthorization.Basic(
                    username = "alice",
                    password = "secret",
                ),
            ),
            capturedConfig,
        )
        assertEquals(listOf("open", "write", "read", "delete", "close"), client.events)
        assertEquals(WebDavWriteMode.Create, client.writtenMode)
        assertTrue(assertNotNull(client.writtenPath).startsWith("health-check/"))
        assertTrue(assertNotNull(client.writtenPath).endsWith(".probe"))
        assertEquals(client.writtenPath, client.readPath)
        assertEquals(client.writtenPath, client.deletedPath)
        assertContentEquals(
            "keyguard-webdav-test\n".encodeToByteArray(),
            assertNotNull(client.writtenBytes),
        )
    }

    @Test
    fun `omits authorization without username`() = runTest {
        val client = FakeWebDavClient()
        var capturedConfig: WebDavClientConfig? = null
        val useCase = CheckWebDavConnectionImpl { config ->
            capturedConfig = config
            client
        }

        useCase(
            CheckWebDavConnectionRequest(
                url = "https://example.com/dav/",
                username = " ",
                password = "secret",
            ),
        ).bind()

        assertEquals(
            WebDavClientConfig(
                baseUrl = "https://example.com/dav/",
                authorization = null,
            ),
            capturedConfig,
        )
    }

    @Test
    fun `propagates read mismatch and closes client`() = runTest {
        val client = FakeWebDavClient(
            readBytes = "unexpected".encodeToByteArray(),
        )
        val useCase = CheckWebDavConnectionImpl { client }

        val error = assertFailsWith<IllegalStateException> {
            useCase(defaultRequest()).bind()
        }

        assertTrue(error.message.orEmpty().contains("different bytes"))
        assertEquals(listOf("open", "write", "read", "delete", "close"), client.events)
    }

    @Test
    fun `suppresses delete failure and closes client`() = runTest {
        val client = FakeWebDavClient(
            deleteError = RuntimeException("delete failed"),
        )
        val useCase = CheckWebDavConnectionImpl { client }

        useCase(defaultRequest()).bind()

        assertEquals(listOf("open", "write", "read", "delete", "close"), client.events)
    }
}

private fun defaultRequest() = CheckWebDavConnectionRequest(
    url = "https://example.com/dav/",
    username = null,
    password = null,
)

private class FakeWebDavClient(
    private val readBytes: ByteArray? = null,
    private val deleteError: Exception? = null,
) : WebDavClient {
    val events = mutableListOf<String>()
    var writtenPath: String? = null
    var writtenMode: WebDavWriteMode? = null
    var writtenBytes: ByteArray? = null
    var readPath: String? = null
    var deletedPath: String? = null

    override suspend fun open(): WebDavOpenResult {
        events += "open"
        return WebDavOpenResult(
            dav = null,
            allow = null,
        )
    }

    override suspend fun stat(
        path: String,
    ): WebDavResource? = error("Not used by this test.")

    override suspend fun read(
        path: String,
        range: WebDavByteRange?,
    ): Source {
        events += "read"
        readPath = path
        return (readBytes
            ?: writtenBytes
            ?: error("Nothing was written before read."))
            .toSource()
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
    ): WebDavResource {
        events += "write"
        writtenPath = path
        writtenMode = mode
        writtenBytes = bytes
        return WebDavResource(
            path = path,
            isCollection = false,
            size = bytes.size.toLong(),
            lastModified = null,
            etag = null,
        )
    }

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        write: suspend (Sink) -> Unit,
    ): WebDavResource = error("Not used by this test.")

    override suspend fun list(
        prefix: String,
    ): List<WebDavResource> = error("Not used by this test.")

    override suspend fun delete(
        path: String,
    ) {
        events += "delete"
        deletedPath = path
        deleteError?.let { throw it }
    }

    override suspend fun close() {
        events += "close"
    }
}
