package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.usecase.ListWebDavDirectory
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOperation
import com.artemchep.keyguard.util.webdav.WebDavResource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class ListWebDavDirectoryImplTest {
    @Test
    fun `lists and maps directory children with no cache client`() = runTest {
        val client = FakeDirectoryWebDavClient()
        var capturedConfig: WebDavClientConfig? = null
        val useCase = ListWebDavDirectoryImpl { config ->
            capturedConfig = config
            client
        }

        val children = useCase(
            ListWebDavDirectory.Request(
                rootUrl = "https://example.com/dav/",
                path = "nested",
                credentials = WebDavCredentials.of("alice", "secret"),
            ),
        ).bind()

        assertEquals(listOf("Folder", "vault.kdbx"), children.map { it.name })
        assertEquals(42L, children[1].size)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000L), children[1].lastModified)
        assertEquals("nested", client.listedPath)
        assertTrue(client.closed)
        assertEquals(true, capturedConfig?.noCache)
        assertEquals(
            WebDavAuthorization.Basic("alice", "secret"),
            capturedConfig?.authorization,
        )
    }

    @Test
    fun `propagates a missing collection error and closes the client`() = runTest {
        val expected = WebDavException.NotFound(
            operation = WebDavOperation.List,
            path = "missing",
            statusCode = 404,
        )
        val client = FakeDirectoryWebDavClient(
            listError = expected,
        )
        val useCase = ListWebDavDirectoryImpl { client }

        val actual = assertFailsWith<WebDavException.NotFound> {
            useCase(
                ListWebDavDirectory.Request(
                    rootUrl = "https://example.com/dav/",
                    path = "missing",
                    credentials = null,
                ),
            ).bind()
        }

        assertTrue(actual === expected)
        assertTrue(client.closed)
    }
}

private class FakeDirectoryWebDavClient(
    private val listError: WebDavException? = null,
) : StubWebDavClient() {
    var listedPath: String? = null
    var closed = false

    override suspend fun listChildren(
        collectionPath: String,
    ): List<WebDavResource> {
        listedPath = collectionPath
        listError?.let { throw it }
        return listOf(
            WebDavResource(
                path = "nested/Folder",
                isCollection = true,
                size = null,
                lastModified = null,
                etag = null,
            ),
            WebDavResource(
                path = "nested/vault.kdbx",
                isCollection = false,
                size = 42L,
                lastModified = Instant.fromEpochSeconds(1_700_000_000L),
                etag = null,
            ),
        )
    }

    override suspend fun close() {
        closed = true
    }
}
