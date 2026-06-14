package com.artemchep.keyguard.util.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KtorWebDavClientTest {
    @Test
    fun `stat encodes path segments and parses DAV properties`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("https://example.com/dav/root/dir/file%20%23.zip", request.url.toString())
            assertEquals("0", request.headers["Depth"])
            respond(
                content = singleMultistatus(
                    href = "/dav/root/dir/file%20%23.zip",
                    properties = """
                        <D:resourcetype/>
                        <D:getcontentlength>42</D:getcontentlength>
                        <D:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</D:getlastmodified>
                        <D:getetag>&quot;abc&quot;</D:getetag>
                    """.trimIndent(),
                ),
                status = MULTI_STATUS,
                headers = headersOf(HttpHeaders.ContentType, "application/xml"),
            )
        }
        val client = testClient(engine)

        val resource = assertNotNull(client.stat("dir/file #.zip"))

        assertEquals("dir/file #.zip", resource.path)
        assertEquals(false, resource.isCollection)
        assertEquals(42L, resource.size)
        assertEquals(Instant.fromEpochSeconds(784903526L), resource.lastModified)
        assertEquals("\"abc\"", resource.etag)
    }

    @Test
    fun `write creates missing parents and publishes via MOVE create-only`() = runTest {
        val payload = "payload".encodeToByteArray()
        val engine = MockEngine { request ->
            when (request.method.value to request.url.encodedPath) {
                "PROPFIND" to "/dav/root/blobs/" -> respond("", status = HttpStatusCode.NotFound)
                "MKCOL" to "/dav/root/blobs/" -> respond("", status = HttpStatusCode.Created)
                "PROPFIND" to "/dav/root/blobs/ab/" -> respond("", status = HttpStatusCode.NotFound)
                "MKCOL" to "/dav/root/blobs/ab/" -> respond("", status = HttpStatusCode.Created)
                "PUT" to request.url.encodedPath -> {
                    assertTrue(request.url.encodedPath.startsWith("/dav/root/blobs/ab/object.zip."))
                    assertTrue(request.url.encodedPath.endsWith(".tmp"))
                    assertEquals(null, request.body.contentLength)
                    assertContentEquals(payload, request.body.asBytes())
                    respond("", status = HttpStatusCode.Created)
                }
                "MOVE" to request.url.encodedPath -> {
                    assertEquals("F", request.headers["Overwrite"])
                    assertEquals(
                        "https://example.com/dav/root/blobs/ab/object.zip",
                        request.headers["Destination"],
                    )
                    respond("", status = HttpStatusCode.Created)
                }
                "PROPFIND" to "/dav/root/blobs/ab/object.zip" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/blobs/ab/object.zip",
                        properties = """
                            <D:resourcetype/>
                            <D:getcontentlength>${payload.size}</D:getcontentlength>
                        """.trimIndent(),
                    ),
                    status = MULTI_STATUS,
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val resource = client.write(
            path = "blobs/ab/object.zip",
            mode = WebDavWriteMode.Create,
            contentLength = null,
        ) { sink ->
            sink.write("pay".encodeToByteArray())
            sink.write("load".encodeToByteArray())
            sink.flush()
        }

        assertEquals("blobs/ab/object.zip", resource.path)
        assertEquals(payload.size.toLong(), resource.size)
        assertEquals(
            listOf("PROPFIND", "MKCOL", "PROPFIND", "MKCOL", "PUT", "MOVE", "PROPFIND"),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `list recursively traverses collections with depth one`() = runTest {
        val engine = MockEngine { request ->
            when (request.method.value to request.url.encodedPath) {
                "PROPFIND" to "/dav/root/snapshots/" -> {
                    val depth = request.headers["Depth"]
                    if (depth == "0") {
                        respond(
                            content = singleMultistatus(
                                href = "/dav/root/snapshots/",
                                properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                            ),
                            status = MULTI_STATUS,
                        )
                    } else {
                        respond(
                            content = multistatus(
                                responseXml(
                                    href = "/dav/root/snapshots/",
                                    properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                                ),
                                responseXml(
                                    href = "/dav/root/snapshots/one.zip",
                                    properties = """
                                        <D:resourcetype/>
                                        <D:getcontentlength>1</D:getcontentlength>
                                    """.trimIndent(),
                                ),
                                responseXml(
                                    href = "/dav/root/snapshots/nested/",
                                    properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                                ),
                            ),
                            status = MULTI_STATUS,
                        )
                    }
                }
                "PROPFIND" to "/dav/root/snapshots/nested/" -> respond(
                    content = multistatus(
                        responseXml(
                            href = "/dav/root/snapshots/nested/",
                            properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                        ),
                        responseXml(
                            href = "/dav/root/snapshots/nested/two.zip",
                            properties = """
                                <D:resourcetype/>
                                <D:getcontentlength>2</D:getcontentlength>
                            """.trimIndent(),
                        ),
                    ),
                    status = MULTI_STATUS,
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val items = client.list("snapshots/")

        assertEquals(
            listOf("snapshots/nested/two.zip", "snapshots/one.zip"),
            items.map { item -> item.path },
        )
        assertEquals(listOf(2L, 1L), items.map { item -> item.size })
    }

    @Test
    fun `range read sends byte range and requires partial content`() = runTest {
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.zip",
                        properties = "<D:resourcetype/><D:getcontentlength>5</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> {
                    assertEquals("bytes=1-3", request.headers[HttpHeaders.Range])
                    assertEquals("identity", request.headers["Accept-Encoding"])
                    respond("abc", status = PARTIAL_CONTENT)
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val bytes = client.read(
            path = "object.zip",
            range = WebDavByteRange(
                offset = 1L,
                length = 3L,
            ),
        ).readBytesAndClose()

        assertContentEquals("abc".encodeToByteArray(), bytes)
    }

    @Test
    fun `range read fails when server ignores range`() = runTest {
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.zip",
                        properties = "<D:resourcetype/><D:getcontentlength>5</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond("abcde", status = HttpStatusCode.OK)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        assertFailsWith<WebDavException.InvalidRange> {
            client.read(
                path = "object.zip",
                range = WebDavByteRange(
                    offset = 1L,
                    length = 3L,
                ),
            )
        }
    }

    @Test
    fun `request cancellation is not converted to transient failure`() = runTest {
        val engine = MockEngine {
            throw CancellationException("cancelled")
        }
        val client = testClient(engine)

        assertFailsWith<CancellationException> {
            client.stat("object.zip")
        }
    }

    @Test
    fun `delete skips collections`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            respond(
                content = singleMultistatus(
                    href = "/dav/root/snapshots/",
                    properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                ),
                status = MULTI_STATUS,
            )
        }
        val client = testClient(engine)

        client.delete("snapshots")

        assertEquals(1, engine.requestHistory.size)
    }

    private fun testClient(
        engine: MockEngine,
    ): KtorWebDavClient = KtorWebDavClient(
        httpClient = HttpClient(engine),
        config = WebDavClientConfig(
            baseUrl = "https://example.com/dav/root/",
        ),
    )

    private fun singleMultistatus(
        href: String,
        properties: String,
    ): String = multistatus(responseXml(href, properties))

    private fun multistatus(
        vararg responses: String,
    ): String = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:multistatus xmlns:D="DAV:">
          ${responses.joinToString("\n")}
        </D:multistatus>
    """.trimIndent()

    private fun responseXml(
        href: String,
        properties: String,
    ): String = """
        <D:response>
          <D:href>$href</D:href>
          <D:propstat>
            <D:prop>
              $properties
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
          </D:propstat>
        </D:response>
    """.trimIndent()

    private suspend fun OutgoingContent.asBytes(): ByteArray = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes()
        is OutgoingContent.ReadChannelContent -> readFrom().toByteArray()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            writeTo(channel)
            channel.flushAndClose()
            channel.toByteArray()
        }
        is OutgoingContent.NoContent -> ByteArray(0)
        else -> error("Unsupported request body type: ${this::class}")
    }

    private fun Source.readBytesAndClose(): ByteArray = try {
        readByteArray()
    } finally {
        close()
    }

    private companion object {
        private val MULTI_STATUS = HttpStatusCode(207, "Multi-Status")
        private val PARTIAL_CONTENT = HttpStatusCode(206, "Partial Content")
    }
}
