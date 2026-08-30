package com.artemchep.keyguard.util.webdav

import com.artemchep.keyguard.util.io.artifact.KEYGUARD_TEMPORARY_ARTIFACT_PREFIX
import com.artemchep.keyguard.util.io.artifact.TemporaryArtifactRole
import com.artemchep.keyguard.util.io.artifact.temporaryArtifactName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    fun `write creates missing parents and publishes via temp upload and MOVE`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/blobs/ab/object.zip"
        var objectStatRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method.value
            when {
                method == "PROPFIND" && path == "/dav/root/blobs/" ->
                    respond("", status = HttpStatusCode.NotFound)
                method == "MKCOL" && path == "/dav/root/blobs/" ->
                    respond("", status = HttpStatusCode.Created)
                method == "PROPFIND" && path == "/dav/root/blobs/ab/" ->
                    respond("", status = HttpStatusCode.NotFound)
                method == "MKCOL" && path == "/dav/root/blobs/ab/" ->
                    respond("", status = HttpStatusCode.Created)
                method == "PROPFIND" && path == objectPath -> {
                    objectStatRequests += 1
                    if (objectStatRequests <= 2) {
                        // Create-mode preflight and pre-MOVE re-check.
                        respond("", status = HttpStatusCode.NotFound)
                    } else {
                        respond(
                            content = singleMultistatus(
                                href = objectPath,
                                properties = """
                                    <D:resourcetype/>
                                    <D:getcontentlength>${payload.size}</D:getcontentlength>
                                """.trimIndent(),
                            ),
                            status = MULTI_STATUS,
                        )
                    }
                }
                method == "PUT" && path.isTempPath() -> {
                    assertEquals(null, request.body.contentLength)
                    assertEquals(null, request.headers[HttpHeaders.IfNoneMatch])
                    assertEquals(null, request.headers[HttpHeaders.IfMatch])
                    assertContentEquals(payload, request.body.asBytes())
                    respond("", status = HttpStatusCode.Created)
                }
                method == "PROPFIND" && path.isTempPath() -> respondFileStat(path)
                method == "MOVE" && path.isTempPath() -> {
                    assertEquals("F", request.headers["Overwrite"])
                    assertEquals(null, request.headers["If"])
                    assertEquals(
                        "https://example.com$objectPath",
                        request.headers["Destination"],
                    )
                    respond("", status = HttpStatusCode.Created)
                }
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
            listOf(
                "PROPFIND", "MKCOL", "PROPFIND", "MKCOL",
                "PROPFIND", "PUT", "PROPFIND", "PROPFIND", "MOVE", "PROPFIND",
            ),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `write sends destination ETag condition on MOVE`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var objectStatRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path, size = payload.size.toLong())
                } else {
                    objectStatRequests += 1
                    val etag = if (objectStatRequests <= 2) "v1" else "v2"
                    respondEtagStat(objectPath, etag, size = payload.size.toLong())
                }
                "PUT" -> {
                    assertTrue(path.isTempPath())
                    assertEquals(null, request.headers[HttpHeaders.IfMatch])
                    assertEquals(null, request.headers[HttpHeaders.IfNoneMatch])
                    assertContentEquals(payload, request.body.asBytes())
                    respond("", status = HttpStatusCode.Created)
                }
                "MOVE" -> {
                    assertEquals("T", request.headers["Overwrite"])
                    assertEquals(
                        "https://example.com$objectPath",
                        request.headers["Destination"],
                    )
                    assertEquals(
                        """<https://example.com$objectPath> (["v1"])""",
                        request.headers["If"],
                    )
                    respond("", status = HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val resource = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v1\""),
        )

        assertEquals("\"v2\"", resource.etag)
        assertEquals(
            listOf("PROPFIND", "PUT", "PROPFIND", "PROPFIND", "MOVE", "PROPFIND"),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `write normalizes weak ETag condition on MOVE`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var objectStatRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    objectStatRequests += 1
                    val etag = if (objectStatRequests <= 2) "v1" else "v2"
                    respondEtagStat(objectPath, etag, weak = true)
                }
                "PUT" -> respond("", status = HttpStatusCode.Created)
                "MOVE" -> {
                    assertEquals(
                        """<https://example.com$objectPath> (["v1"])""",
                        request.headers["If"],
                    )
                    respond("", status = HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val resource = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("W/\"v1\""),
        )

        assertEquals("W/\"v2\"", resource.etag)
        assertEquals(
            listOf("PROPFIND", "PUT", "PROPFIND", "PROPFIND", "MOVE", "PROPFIND"),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `MOVE precondition conflict is verified against a fresh destination stat`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var objectStatRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    objectStatRequests += 1
                    // The destination changes between the pre-MOVE re-check
                    // and the 412 disambiguation stat.
                    val etag = if (objectStatRequests <= 2) "v1" else "v2"
                    respondEtagStat(objectPath, etag)
                }
                "PUT" -> respond("", status = HttpStatusCode.Created)
                "MOVE" -> respond("", status = HttpStatusCode.PreconditionFailed)
                "DELETE" -> {
                    assertTrue(path.isTempPath())
                    respond("", status = HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        assertFailsWith<WebDavException.PreconditionFailed> {
            client.write(
                path = "object.zip",
                bytes = payload,
                precondition = WebDavWritePrecondition("\"v1\""),
            )
        }
        assertEquals(
            listOf("PROPFIND", "PUT", "PROPFIND", "PROPFIND", "MOVE", "PROPFIND", "PROPFIND", "DELETE"),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `MOVE 412 with unchanged destination degrades to unconditional MOVE`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var etag = "v1"
        var moveAttempts = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    respondEtagStat(objectPath, etag)
                }
                "PUT" -> respond("", status = HttpStatusCode.Created)
                "MOVE" -> {
                    moveAttempts += 1
                    when (moveAttempts) {
                        1 -> {
                            // A strict server rejects the condition even
                            // though the destination has not changed.
                            assertEquals(
                                """<https://example.com$objectPath> (["v1"])""",
                                request.headers["If"],
                            )
                            respond("", status = HttpStatusCode.PreconditionFailed)
                        }
                        2 -> {
                            assertEquals(null, request.headers["If"])
                            assertEquals("T", request.headers["Overwrite"])
                            etag = "v2"
                            respond("", status = HttpStatusCode.NoContent)
                        }
                        else -> {
                            // The degradation is sticky for this client.
                            assertEquals(null, request.headers["If"])
                            etag = "v3"
                            respond("", status = HttpStatusCode.NoContent)
                        }
                    }
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val first = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v1\""),
        )
        assertEquals("\"v2\"", first.etag)

        val second = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v2\""),
        )
        assertEquals("\"v3\"", second.etag)
        assertEquals(3, moveAttempts)
    }

    @Test
    fun `atomic write fails without replaying payload when MOVE is unsupported`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var writeCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respond(
                        content = singleMultistatus(
                            href = path,
                            properties = "<D:resourcetype/><D:getcontentlength>${payload.size}</D:getcontentlength>",
                        ),
                        status = MULTI_STATUS,
                    )
                } else {
                    respond("", status = HttpStatusCode.NotFound)
                }
                "PUT" -> {
                    assertTrue(path.isTempPath())
                    assertContentEquals(payload, request.body.asBytes())
                    respond("", status = HttpStatusCode.Created)
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val firstError = assertFailsWith<WebDavException.AtomicWriteUnsupported> {
            client.write(
                path = "object.zip",
                mode = WebDavWriteMode.Create,
                contentLength = payload.size.toLong(),
            ) { sink ->
                writeCalls += 1
                sink.write(payload)
            }
        }
        assertEquals(HttpStatusCode.MethodNotAllowed.value, firstError.statusCode)

        assertFailsWith<WebDavException.AtomicWriteUnsupported> {
            client.write(
                path = "object.zip",
                mode = WebDavWriteMode.Create,
                contentLength = payload.size.toLong(),
            ) { sink ->
                writeCalls += 1
                sink.write(payload)
            }
        }

        assertEquals(1, writeCalls)
        assertTrue(
            engine.requestHistory
                .filter { request -> request.method == HttpMethod.Put }
                .all { request -> request.url.encodedPath.isTempPath() },
        )
    }

    @Test
    fun `atomic create maps MOVE 412 to already exists without retry or destination stat`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var moveCalls = 0
        var destinationStatCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    destinationStatCalls += 1
                    respond("", status = HttpStatusCode.NotFound)
                }
                "PUT" -> respond("", status = HttpStatusCode.Created)
                "MOVE" -> {
                    moveCalls += 1
                    assertEquals("F", request.headers["Overwrite"])
                    respond("", status = HttpStatusCode.PreconditionFailed)
                }
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        assertFailsWith<WebDavException.AlreadyExists> {
            client.write(
                path = "object.zip",
                mode = WebDavWriteMode.Create,
                bytes = payload,
            )
        }

        assertEquals(1, moveCalls)
        assertEquals(2, destinationStatCalls)
    }

    @Test
    fun `write falls back to conditional PUT when MOVE is unsupported`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var etag = "v1"
        var objectPutRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    respondEtagStat(objectPath, etag)
                }
                "PUT" -> if (path.isTempPath()) {
                    respond("", status = HttpStatusCode.Created)
                } else {
                    objectPutRequests += 1
                    val expectedEtag = if (objectPutRequests == 1) "\"v1\"" else "\"v2\""
                    assertEquals(expectedEtag, request.headers[HttpHeaders.IfMatch])
                    assertContentEquals(payload, request.body.asBytes())
                    etag = if (objectPutRequests == 1) "v2" else "v3"
                    respond("", status = HttpStatusCode.NoContent)
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> {
                    assertTrue(path.isTempPath())
                    respond("", status = HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(
            engine = engine,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        )

        val first = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v1\""),
        )
        assertEquals("\"v2\"", first.etag)

        // The fallback is sticky: the next write goes straight to PUT.
        val second = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v2\""),
        )
        assertEquals("\"v3\"", second.etag)
        assertEquals(
            listOf(
                "PROPFIND", "PUT", "PROPFIND", "PROPFIND", "MOVE",
                "PROPFIND", "DELETE", "PUT", "PROPFIND",
                "PROPFIND", "PUT", "PROPFIND",
            ),
            engine.requestHistory.map { request -> request.method.value },
        )
    }

    @Test
    fun `lossy create fallback retains If-None-Match condition`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var destinationExists = false
        var destinationPutCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> when {
                    path.isTempPath() -> respond(
                        content = singleMultistatus(
                            href = path,
                            properties = "<D:resourcetype/><D:getcontentlength>${payload.size}</D:getcontentlength>",
                        ),
                        status = MULTI_STATUS,
                    )
                    destinationExists -> respond(
                        content = singleMultistatus(
                            href = objectPath,
                            properties = "<D:resourcetype/><D:getcontentlength>${payload.size}</D:getcontentlength>",
                        ),
                        status = MULTI_STATUS,
                    )
                    else -> respond("", status = HttpStatusCode.NotFound)
                }
                "PUT" -> if (path.isTempPath()) {
                    respond("", status = HttpStatusCode.Created)
                } else {
                    destinationPutCalls += 1
                    assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                    assertEquals(null, request.headers[HttpHeaders.IfMatch])
                    destinationExists = true
                    respond("", status = HttpStatusCode.Created)
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(
            engine = engine,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        )

        val resource = client.write(
            path = "object.zip",
            mode = WebDavWriteMode.Create,
            bytes = payload,
        )

        assertEquals(payload.size.toLong(), resource.size)
        assertEquals(1, destinationPutCalls)
    }

    @Test
    fun `lossy create maps PUT 412 to already exists without retry or destination stat`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var destinationPutCalls = 0
        var destinationStatCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    destinationStatCalls += 1
                    respond("", status = HttpStatusCode.NotFound)
                }
                "PUT" -> if (path.isTempPath()) {
                    respond("", status = HttpStatusCode.Created)
                } else {
                    destinationPutCalls += 1
                    assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                    respond("", status = HttpStatusCode.PreconditionFailed)
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(
            engine = engine,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        )

        assertFailsWith<WebDavException.AlreadyExists> {
            client.write(
                path = "object.zip",
                mode = WebDavWriteMode.Create,
                bytes = payload,
            )
        }

        assertEquals(1, destinationPutCalls)
        assertEquals(2, destinationStatCalls)
    }

    @Test
    fun `replace PUT degradation does not weaken a later create`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        val createPath = "/dav/root/new.zip"
        var etag = "v1"
        var objectPutRequests = 0
        var createExists = false
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> when {
                    path.isTempPath() -> respondFileStat(path)
                    path == createPath && !createExists -> respond(
                        "",
                        status = HttpStatusCode.NotFound,
                    )
                    path == createPath -> respond(
                        content = singleMultistatus(
                            href = createPath,
                            properties = "<D:getcontentlength>${payload.size}</D:getcontentlength>",
                        ),
                        status = MULTI_STATUS,
                    )
                    else -> respondEtagStat(objectPath, etag)
                }
                "PUT" -> when {
                    path.isTempPath() -> respond("", status = HttpStatusCode.Created)
                    path == createPath -> {
                        assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                        assertEquals(null, request.headers[HttpHeaders.IfMatch])
                        createExists = true
                        respond("", status = HttpStatusCode.Created)
                    }
                    else -> {
                        objectPutRequests += 1
                        if (objectPutRequests == 1) {
                            // A strict server rejects the condition even though
                            // the destination has not changed.
                            assertEquals("\"v1\"", request.headers[HttpHeaders.IfMatch])
                            respond("", status = HttpStatusCode.PreconditionFailed)
                        } else {
                            assertEquals(null, request.headers[HttpHeaders.IfMatch])
                            etag = "v2"
                            respond("", status = HttpStatusCode.NoContent)
                        }
                    }
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(
            engine = engine,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        )

        val resource = client.write(
            path = "object.zip",
            bytes = payload,
            precondition = WebDavWritePrecondition("\"v1\""),
        )

        assertEquals("\"v2\"", resource.etag)
        assertEquals(2, objectPutRequests)

        val created = client.write(
            path = "new.zip",
            mode = WebDavWriteMode.Create,
            bytes = payload,
        )
        assertEquals(payload.size.toLong(), created.size)
    }

    @Test
    fun `fallback PUT conflict is verified against a fresh destination stat`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.zip"
        var objectStatRequests = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path)
                } else {
                    objectStatRequests += 1
                    // The destination changes between the pre-PUT checks and
                    // the 412 disambiguation stat.
                    val etag = if (objectStatRequests <= 2) "v1" else "v2"
                    respondEtagStat(objectPath, etag)
                }
                "PUT" -> if (path.isTempPath()) {
                    respond("", status = HttpStatusCode.Created)
                } else {
                    respond("", status = HttpStatusCode.PreconditionFailed)
                }
                "MOVE" -> respond("", status = HttpStatusCode.MethodNotAllowed)
                "DELETE" -> respond("", status = HttpStatusCode.NoContent)
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(
            engine = engine,
            writeStrategy = WebDavWriteStrategy.AllowLossy,
        )

        assertFailsWith<WebDavException.PreconditionFailed> {
            client.write(
                path = "object.zip",
                bytes = payload,
                precondition = WebDavWritePrecondition("\"v1\""),
            )
        }
    }

    @Test
    fun `conditional write rejects stale weak ETag before upload`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            respond(
                content = singleMultistatus(
                    href = "/dav/root/object.zip",
                    properties = "<D:getetag>W/&quot;current&quot;</D:getetag>",
                ),
                status = MULTI_STATUS,
            )
        }
        val client = testClient(engine)

        assertFailsWith<WebDavException.PreconditionFailed> {
            client.write(
                path = "object.zip",
                bytes = "payload".encodeToByteArray(),
                precondition = WebDavWritePrecondition("W/\"stale\""),
            )
        }

        assertEquals(listOf("PROPFIND"), engine.requestHistory.map { request -> request.method.value })
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
                                    href = "/dav/root/snapshots/${
                                        temporaryArtifactName(
                                            TemporaryArtifactRole.New,
                                            "123e4567-e89b-42d3-a456-426614174000",
                                        )
                                    }",
                                    properties = """
                                        <D:resourcetype/>
                                        <D:getcontentlength>99</D:getcontentlength>
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
    fun `list returns empty for a missing start collection`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("/dav/root/missing/", request.url.encodedPath)
            assertEquals("0", request.headers["Depth"])
            respond("", status = HttpStatusCode.NotFound)
        }
        val client = testClient(engine)

        assertTrue(client.list("missing/").isEmpty())
    }

    @Test
    fun `list skips a collection that disappears during traversal`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath to request.headers["Depth"]) {
                "/dav/root/snapshots/" to "0" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/snapshots/",
                        properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                    ),
                    status = MULTI_STATUS,
                )

                "/dav/root/snapshots/" to "1" -> respond(
                    content = multistatus(
                        responseXml(
                            href = "/dav/root/snapshots/",
                            properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                        ),
                        responseXml(
                            href = "/dav/root/snapshots/one.zip",
                            properties = "<D:resourcetype/>",
                        ),
                        responseXml(
                            href = "/dav/root/snapshots/disappeared/",
                            properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                        ),
                    ),
                    status = MULTI_STATUS,
                )

                "/dav/root/snapshots/disappeared/" to "1" ->
                    respond("", status = HttpStatusCode.NotFound)

                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        assertEquals(
            listOf("snapshots/one.zip"),
            client.list("snapshots/").map { item -> item.path },
        )
    }

    @Test
    fun `list children returns only direct safe children`() = runTest {
        val tempName = temporaryArtifactName(
            TemporaryArtifactRole.New,
            "123e4567-e89b-42d3-a456-426614174000",
        )
        val engine = MockEngine { request ->
            assertEquals(HttpMethod("PROPFIND"), request.method)
            assertEquals("/dav/root/folder/", request.url.encodedPath)
            assertEquals("1", request.headers["Depth"])
            respond(
                content = listChildrenMultistatus(tempName),
                status = MULTI_STATUS,
            )
        }
        val client = testClient(engine)

        val items = client.listChildren("folder/")

        assertEquals(
            listOf("folder/sub folder", "folder/vault ✓.kdbx"),
            items.map { item -> item.path },
        )
        assertEquals(listOf(true, false), items.map { item -> item.isCollection })
    }

    @Test
    fun `list children throws not found for a missing collection`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("1", request.headers["Depth"])
            respond("", status = HttpStatusCode.NotFound)
        }
        val client = testClient(engine)

        val error = assertFailsWith<WebDavException.NotFound> {
            client.listChildren("missing")
        }

        assertEquals(WebDavOperation.List, error.operation)
        assertEquals("missing", error.path)
        assertEquals(HttpStatusCode.NotFound.value, error.statusCode)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `list children returns empty when collection has no children`() = runTest {
        val engine = MockEngine {
            respond(
                content = singleMultistatus(
                    href = "/dav/root/empty/",
                    properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                ),
                status = MULTI_STATUS,
            )
        }
        val client = testClient(engine)

        assertTrue(client.listChildren("empty").isEmpty())
    }

    @Test
    fun `relative path normalizer strips slashes and rejects traversal`() {
        assertEquals("one/two", normalizeWebDavRelativePath("/one/two/"))
        assertEquals("", normalizeWebDavRelativePath("/"))
        assertFailsWith<IllegalArgumentException> {
            normalizeWebDavRelativePath("one/../two")
        }
    }

    @Test
    fun `resource URL resolver encodes relative segments`() {
        assertEquals(
            "https://example.com/dav/root/folder%20%23/vault%20%E2%9C%93.kdbx",
            resolveWebDavResourceUrl(
                baseUrl = "https://example.com/dav/root",
                path = "folder #/vault ✓.kdbx",
            ),
        )
        assertEquals(
            "https://example.com/dav/root/folder%20%23/",
            resolveWebDavResourceUrl(
                baseUrl = "https://example.com/dav/root/",
                path = "folder #",
                collection = true,
            ),
        )
        assertEquals(
            "https://example.com/dav/root/folder/?download=1",
            resolveWebDavResourceUrl(
                baseUrl = "https://example.com/dav/root?download=1#fragment",
                path = "folder",
                collection = true,
            ),
        )
    }

    @Test
    fun `requests preserve base URL query parameters`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/dav/root/", request.url.encodedPath)
            assertEquals(listOf("one", "two"), request.url.parameters.getAll("token"))
            assertEquals("read/write", request.url.parameters["scope"])
            respond(
                content = singleMultistatus(
                    href = "/dav/root/",
                    properties = "<D:resourcetype><D:collection/></D:resourcetype>",
                ),
                status = MULTI_STATUS,
            )
        }
        val client = testClient(
            engine = engine,
            baseUrl = "https://example.com/dav/root?token=one&token=two&scope=read%2Fwrite#fragment",
        )

        assertTrue(client.listChildren("").isEmpty())
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
                    respond(
                        content = "abc",
                        status = PARTIAL_CONTENT,
                        headers = headersOf(HttpHeaders.ContentRange, "bytes 1-3/5"),
                    )
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
    fun `read retries malformed DAV metadata response`() = runTest {
        val payload = "payload".encodeToByteArray()
        var propfindAttempts = 0
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> {
                    propfindAttempts += 1
                    if (propfindAttempts == 1) {
                        respond("<D:multistatus", status = MULTI_STATUS)
                    } else {
                        respond(
                            content = singleMultistatus(
                                href = "/dav/root/object.kdbx",
                                properties = """
                                    <D:resourcetype/>
                                    <D:getcontentlength>${payload.size}</D:getcontentlength>
                                    <D:getetag>&quot;v2&quot;</D:getetag>
                                """.trimIndent(),
                            ),
                            status = MULTI_STATUS,
                        )
                    }
                }
                "GET" -> {
                    assertEquals("identity", request.headers["Accept-Encoding"])
                    assertEquals("\"v2\"", request.headers[HttpHeaders.IfMatch])
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(payload.size.toString()),
                            HttpHeaders.ETag to listOf("\"v2\""),
                        ),
                    )
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val actual = client.read("object.kdbx").readBytesAndClose()

        assertContentEquals(payload, actual)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET"), engine.requestHistory.map { it.method.value })
    }

    @Test
    fun `read retries when GET size disagrees with DAV metadata`() = runTest {
        val payload = "complete".encodeToByteArray()
        var getAttempts = 0
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = """
                            <D:resourcetype/>
                            <D:getcontentlength>${payload.size}</D:getcontentlength>
                        """.trimIndent(),
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> {
                    getAttempts += 1
                    val content = if (getAttempts == 1) payload.copyOf(3) else payload
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, content.size.toString()),
                    )
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val actual = client.read("object.kdbx").readBytesAndClose()

        assertContentEquals(payload, actual)
        assertEquals(listOf("PROPFIND", "GET", "PROPFIND", "GET"), engine.requestHistory.map { it.method.value })
    }

    @Test
    fun `read returns a streaming source before response EOF`() = runTest {
        val payload = "streaming".encodeToByteArray()
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeFully(payload)
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>${payload.size}</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val source = withContext(Dispatchers.Default) {
            withTimeout(1_000L) {
                async { client.read("object.kdbx") }.await()
            }
        }

        assertTrue(!responseBody.isClosedForWrite)
        source.close()
    }

    @Test
    fun `streaming read detects a cleanly truncated response`() = runTest {
        val payload = "short".encodeToByteArray()
        val expectedSize = payload.size + 3
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = payload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val source = client.read("object.kdbx")

        assertFailsWith<WebDavException.Protocol> {
            source.readBytesAndClose()
        }
        assertEquals(listOf("PROPFIND", "GET"), engine.requestHistory.map { it.method.value })
    }

    @Test
    fun `streaming read does not expose bytes beyond the declared size`() = runTest {
        val payload = "too long".encodeToByteArray()
        val expectedSize = payload.size - 3
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = payload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)
        val source = client.read("object.kdbx")

        assertFailsWith<WebDavException.Protocol> {
            source.readBytesAndClose()
        }
    }

    @Test
    fun `streaming read maps a response body failure to transient`() = runTest {
        val expectedSize = 8
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeFully("part".encodeToByteArray())
        responseBody.close(IllegalStateException("connection closed"))
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val source = client.read("object.kdbx")

        assertFailsWith<WebDavException.Transient> {
            source.readBytesAndClose()
        }
    }

    @Test
    fun `streaming read maps a body failure wrapping a cancellation to transient`() = runTest {
        val expectedSize = 8
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeFully("part".encodeToByteArray())
        responseBody.close(
            IllegalStateException(
                "engine torn down",
                CancellationException("request job cancelled"),
            ),
        )
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val source = client.read("object.kdbx")

        assertFailsWith<WebDavException.Transient> {
            source.readBytesAndClose()
        }
    }

    @Test
    fun `streaming read maps a bare body cancellation to transient for an active consumer`() = runTest {
        val expectedSize = 8
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeFully("part".encodeToByteArray())
        responseBody.close(CancellationException("request job cancelled"))
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val source = client.read("object.kdbx")

        assertFailsWith<WebDavException.Transient> {
            source.readBytesAndClose()
        }
    }

    @Test
    fun `cancelling consumer cancels a blocked streaming read`() = runTest {
        val expectedSize = 8
        val responseBody = ByteChannel(autoFlush = true)
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = "<D:resourcetype/><D:getcontentlength>$expectedSize</D:getcontentlength>",
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, expectedSize.toString()),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)
        val sourceReady = CompletableDeferred<Unit>()
        val consumer = launch(Dispatchers.Default) {
            client.read("object.kdbx").use { source ->
                sourceReady.complete(Unit)
                source.readByteArray()
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(1_000L) {
                sourceReady.await()
            }
            withTimeout(1_000L) {
                consumer.cancelAndJoin()
            }
        }

        assertTrue(consumer.isCancelled)
    }

    @Test
    fun `read retries without condition when server rejects conditional GET`() = runTest {
        val payload = "payload".encodeToByteArray()
        var getAttempts = 0
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = """
                            <D:resourcetype/>
                            <D:getcontentlength>${payload.size}</D:getcontentlength>
                            <D:getetag>&quot;v2&quot;</D:getetag>
                        """.trimIndent(),
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> {
                    getAttempts += 1
                    if (getAttempts == 1) {
                        assertEquals("\"v2\"", request.headers[HttpHeaders.IfMatch])
                        respond("", status = HttpStatusCode.PreconditionFailed)
                    } else {
                        assertEquals(null, request.headers[HttpHeaders.IfMatch])
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                        )
                    }
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val actual = client.read("object.kdbx").readBytesAndClose()

        assertContentEquals(payload, actual)
        assertEquals(listOf("PROPFIND", "GET", "PROPFIND", "GET"), engine.requestHistory.map { it.method.value })
    }

    @Test
    fun `write retries post MOVE size verification`() = runTest {
        val payload = "payload".encodeToByteArray()
        val objectPath = "/dav/root/object.kdbx"
        var finalStatAttempts = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (request.method.value) {
                "PUT" -> respond("", status = HttpStatusCode.Created)
                "MOVE" -> respond("", status = HttpStatusCode.Created)
                "PROPFIND" -> if (path.isTempPath()) {
                    respondFileStat(path, size = payload.size.toLong())
                } else {
                    finalStatAttempts += 1
                    val size = if (finalStatAttempts == 1) payload.size - 1 else payload.size
                    respond(
                        content = singleMultistatus(
                            href = objectPath,
                            properties = """
                                <D:resourcetype/>
                                <D:getcontentlength>$size</D:getcontentlength>
                            """.trimIndent(),
                        ),
                        status = MULTI_STATUS,
                    )
                }
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val resource = client.write("object.kdbx", bytes = payload)

        assertEquals(payload.size.toLong(), resource.size)
        assertEquals(
            listOf("PUT", "PROPFIND", "MOVE", "PROPFIND", "PROPFIND"),
            engine.requestHistory.map { it.method.value },
        )
    }

    @Test
    fun `read accepts a weakened response ETag for strong metadata ETag`() = runTest {
        val payload = "payload".encodeToByteArray()
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PROPFIND" -> respond(
                    content = singleMultistatus(
                        href = "/dav/root/object.kdbx",
                        properties = """
                            <D:resourcetype/>
                            <D:getcontentlength>${payload.size}</D:getcontentlength>
                            <D:getetag>&quot;v2&quot;</D:getetag>
                        """.trimIndent(),
                    ),
                    status = MULTI_STATUS,
                )
                "GET" -> respond(
                    content = payload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf(payload.size.toString()),
                        HttpHeaders.ETag to listOf("W/\"v2\""),
                    ),
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val client = testClient(engine)

        val actual = client.read("object.kdbx").readBytesAndClose()

        assertContentEquals(payload, actual)
        assertEquals(listOf("PROPFIND", "GET"), engine.requestHistory.map { it.method.value })
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

    private fun String.isTempPath(): Boolean =
        substringAfterLast('/').let { name ->
            name.startsWith(
                "${KEYGUARD_TEMPORARY_ARTIFACT_PREFIX}v1u-${TemporaryArtifactRole.New.token}-",
            ) && name.endsWith(".tmp")
        }

    private fun testClient(
        engine: MockEngine,
        writeStrategy: WebDavWriteStrategy = WebDavWriteStrategy.RequireAtomic,
        baseUrl: String = "https://example.com/dav/root/",
    ): KtorWebDavClient = KtorWebDavClient(
        httpClient = HttpClient(engine),
        config = WebDavClientConfig(
            baseUrl = baseUrl,
            writeStrategy = writeStrategy,
        ),
    )

    /**
     * Responds to a PROPFIND with a minimal single-file multistatus: an empty
     * resource type plus an optional size. The shape every uploaded temp
     * sibling reports back.
     */
    private fun MockRequestHandleScope.respondFileStat(
        href: String,
        size: Long? = null,
    ): HttpResponseData = respond(
        content = singleMultistatus(
            href = href,
            properties = listOfNotNull(
                "<D:resourcetype/>",
                size?.let { "<D:getcontentlength>$it</D:getcontentlength>" },
            ).joinToString("\n"),
        ),
        status = MULTI_STATUS,
    )

    /**
     * Responds to a destination PROPFIND with a stat carrying the given ETag
     * value, entity-quoted, optionally weak.
     */
    private fun MockRequestHandleScope.respondEtagStat(
        href: String,
        etag: String,
        size: Long? = null,
        weak: Boolean = false,
    ): HttpResponseData {
        val quoted = "&quot;$etag&quot;"
        return respond(
            content = singleMultistatus(
                href = href,
                properties = listOfNotNull(
                    "<D:resourcetype/>",
                    size?.let { "<D:getcontentlength>$it</D:getcontentlength>" },
                    "<D:getetag>${if (weak) "W/$quoted" else quoted}</D:getetag>",
                ).joinToString("\n"),
            ),
            status = MULTI_STATUS,
        )
    }

    private fun singleMultistatus(
        href: String,
        properties: String,
    ): String = multistatus(responseXml(href, properties))

    private fun listChildrenMultistatus(
        tempName: String,
    ): String = multistatus(
        responseXml(
            href = "/dav/root/folder/?ignored=true",
            properties = "<D:resourcetype><D:collection/></D:resourcetype>",
        ),
        responseXml(
            href = "https://example.com/dav/root/folder/sub%20folder/",
            properties = "<D:resourcetype><D:collection/></D:resourcetype>",
        ),
        responseXml(
            href = "/dav/root/folder/vault%20%E2%9C%93.kdbx#fragment",
            properties = "<D:resourcetype/><D:getcontentlength>42</D:getcontentlength>",
        ),
        responseXml(
            href = "/dav/root/folder/sub/deep.kdbx",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "/dav/root/sibling.kdbx",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "https://evil.example/dav/root/folder/stolen.kdbx",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "http://example.com/dav/root/folder/insecure.kdbx",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "https://example.com:8443/dav/root/folder/wrong-port.kdbx",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "/dav/root/folder/$tempName",
            properties = "<D:resourcetype/>",
        ),
        responseXml(
            href = "/dav/root/folder/vault%20%E2%9C%93.kdbx",
            properties = "<D:resourcetype/><D:getcontentlength>42</D:getcontentlength>",
        ),
    )

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
