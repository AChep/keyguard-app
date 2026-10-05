package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.KtorS3Client
import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials
import com.artemchep.keyguard.util.s3.S3Exception
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Keep engine/status cases separate so each has its own result and deadline.
@Suppress("TooManyFunctions")
class KtorS3ClientHttpE2eTest {
    private val engines = listOf("cio", "okhttp")

    @Test
    fun `redirects never reach the Location destination`() = runTest {
        for (engine in engines) {
            for (status in listOf(301, 307)) {
                S3HttpFixture { _, socket -> socket.respond() }.use { destination ->
                    S3HttpFixture { _, socket ->
                        socket.respond(status, mapOf(
                            "Location" to "${destination.endpoint}/moved",
                            "x-amz-bucket-region" to "eu-west-1",
                        ))
                    }.use { server ->
                        withClient(engine, server) { client ->
                            val operations: List<suspend () -> Unit> = listOf(
                                { client.headObject("key") }, { client.getObject("key").close() },
                                { client.putObject("key", byteArrayOf(1)) },
                                { client.listObjects() }, { client.deleteObject("key") },
                            )
                            operations.forEach { operation ->
                                val error = assertFailsWith<S3Exception.WrongRegion>(engine) { operation() }
                                assertEquals("eu-west-1", error.expectedRegion)
                            }
                        }
                        assertEquals(5, server.requests.size)
                        assertTrue(destination.requests.isEmpty(), engine)
                    }
                }
            }
        }
    }

    @Test
    fun `truncated fixed length and chunked bodies fail during consumption`() = runTest {
        for (engine in engines) {
            for (chunked in listOf(false, true)) {
                S3HttpFixture { _, socket ->
                    if (chunked) {
                        socket.writeHeaders(headers = mapOf("Transfer-Encoding" to "chunked"))
                        socket.getOutputStream().write("5\r\nabc".encodeToByteArray())
                    } else {
                        socket.respond(headers = mapOf("Content-Length" to "5"), body = "abc")
                    }
                }.use { server ->
                    withClient(engine, server) { client ->
                        val error = assertFailsWith<S3Exception>("$engine chunked=$chunked") {
                            client.getObject("key").use { it.readByteArray() }
                        }
                        assertTrue(error is S3Exception.Protocol || error is S3Exception.Transient)
                        assertTrue(error.retryable)
                    }
                }
            }
        }
    }

    @Test
    fun `socket timeouts map stalled bodies to transient errors`() = runTest {
        for (engine in engines) {
            S3HttpFixture { _, socket ->
                socket.writeHeaders(headers = mapOf("Content-Length" to "100"))
                socket.awaitDisconnect()
            }.use { server ->
                withClient(engine, server, socketTimeoutMillis = 500) { client ->
                    assertFailsWith<S3Exception.Transient>(engine) {
                        client.getObject("key").use { it.readByteArray() }
                    }
                }
            }
        }
    }

    @Test
    fun `cancelling blocked readers disconnects and leaves the client reusable`() = runTest {
        withContext(Dispatchers.IO) {
            withTimeout(10.seconds) {
                for (engine in engines) {
                    val disconnected = CompletableDeferred<Unit>()
                    val ready = CompletableDeferred<Unit>()
                    S3HttpFixture { request, socket ->
                        if (request.target.endsWith("/blocked")) {
                            socket.writeHeaders(headers = mapOf("Content-Length" to "100"))
                            socket.awaitDisconnect()
                            disconnected.complete(Unit)
                        } else {
                            socket.respond(body = "next")
                        }
                    }.use { server ->
                        withClient(engine, server) { client ->
                            val consumer = launch {
                                client.getObject("blocked").use {
                                    ready.complete(Unit)
                                    it.readByteArray()
                                }
                            }
                            ready.await()
                            consumer.cancelAndJoin()
                            server.awaitCompletion(disconnected)
                            assertTrue(consumer.isCancelled)
                            assertEquals("next", client.getObject("next").use { it.readByteArray().decodeToString() })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `CIO bounds unfinished listing bodies and releases the connection`() =
        assertOversizedResponseIsBounded("cio", 200)

    @Test
    fun `CIO bounds unfinished error bodies and releases the connection`() =
        assertOversizedResponseIsBounded("cio", 503)

    @Test
    fun `OkHttp bounds unfinished listing bodies and releases the connection`() =
        assertOversizedResponseIsBounded("okhttp", 200)

    @Test
    fun `OkHttp bounds unfinished error bodies and releases the connection`() =
        assertOversizedResponseIsBounded("okhttp", 503)

    @Suppress("TooGenericExceptionCaught")
    private fun assertOversizedResponseIsBounded(engine: String, status: Int) = runTest {
        withContext(Dispatchers.IO) {
            var phase = "rejecting the oversized response"
            try {
                withTimeout(15.seconds) {
                    val disconnected = CompletableDeferred<Unit>()
                    S3HttpFixture { request, socket ->
                        if (request.target.endsWith("/next")) {
                            socket.respond(body = "next")
                        } else {
                            socket.writeHeaders(status)
                            socket.writeUntilDisconnected()
                            disconnected.complete(Unit)
                        }
                    }.use { server ->
                        withClient(engine, server) { client ->
                            if (status == 200) {
                                assertFailsWith<S3Exception.Protocol> { client.listObjects() }
                            } else {
                                assertFailsWith<S3Exception.Transient> { client.getObject("key") }
                            }
                            phase = "observing EOF or reset"
                            server.awaitCompletion(disconnected)
                            phase = "reusing the client"
                            assertEquals("next", client.getObject("next").use { it.readByteArray().decodeToString() })
                        }
                        phase = "checking requests"
                        assertEquals(2, server.requests.size)
                    }
                }
            } catch (e: Throwable) {
                throw AssertionError("$engine status=$status failed while $phase", e)
            }
        }
    }

    @Test
    fun `lost PUT response is not replayed even with the retry plugin installed`() = runTest {
        for (engine in engines) {
            // The fixture consumes the complete upload, then closes without headers.
            S3HttpFixture { _, _ -> }.use { server ->
                withClient(engine, server, retries = true) { client ->
                    assertFailsWith<S3Exception.Transient>(engine) { client.putObject("key", byteArrayOf(1, 2, 3)) }
                }
                assertEquals(1, server.requests.size, engine)
                assertContentEquals(byteArrayOf(1, 2, 3), server.requests.single().body)
            }
        }
    }

    @Test
    fun `wire paths query headers payload and signatures agree for both engines`() = runTest {
        for (engine in engines) {
            S3HttpFixture { request, socket ->
                when {
                    request.method == "HEAD" -> socket.respond(headers = mapOf("ETag" to "\"etag\""))
                    request.method == "PUT" -> socket.respond(headers = mapOf("ETag" to "\"etag\""))
                    '?' in request.target -> socket.respond(
                        body = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>",
                    )
                    request.method == "GET" -> socket.respond(
                        206, mapOf("Content-Range" to "bytes 1-2/4"), "bc",
                    )
                    else -> socket.respond(204)
                }
            }.use { server ->
                withClient(engine, server) { client ->
                    val key = "dir/é😀 +%2F?#.bin"
                    client.putObject(key, byteArrayOf(0, 1, -1))
                    client.headObject(key)
                    val range = client.getObject(key, S3ByteRange(1, 2)).use { it.readByteArray().decodeToString() }
                    assertEquals("bc", range)
                    client.listObjects(prefix = "a+%/é", delimiter = "/", continuationToken = "a%2F+/==", maxKeys = 2)
                    client.deleteObject(key)
                }
                val encodedPath = "/bucket/dir/%C3%A9%F0%9F%98%80%20%2B%252F%3F%23.bin"
                assertEquals(List(3) { encodedPath }, server.requests.take(3).map { it.target })
                assertEquals(
                    "/bucket?continuation-token=a%252F%2B%2F%3D%3D&delimiter=%2F&encoding-type=url" +
                        "&list-type=2&max-keys=2&prefix=a%2B%25%2F%C3%A9",
                    server.requests[3].target,
                )
                assertEquals(encodedPath, server.requests[4].target)
                server.requests.forEach { request ->
                    assertEquals("identity", request.headers["accept-encoding"])
                    assertNull(request.headers["cache-control"])
                    request.verifySignature()
                }
            }
        }
    }

    private suspend fun withClient(
        engine: String,
        server: S3HttpFixture,
        socketTimeoutMillis: Long = 5000,
        retries: Boolean = false,
        block: suspend (KtorS3Client) -> Unit,
    ) {
        val http = if (engine == "cio") HttpClient(CIO) else HttpClient(OkHttp)
        val configured = http.config {
            followRedirects = false
            install(HttpTimeout) {
                this.socketTimeoutMillis = socketTimeoutMillis
                requestTimeoutMillis = 8000
            }
            if (retries) install(HttpRequestRetry) { retryOnException(maxRetries = 3) }
        }
        val client = KtorS3Client(
            configured,
            S3ClientConfig(
                endpoint = server.endpoint,
                bucket = "bucket",
                credentials = S3Credentials("test-access", "test-secret"),
            ),
            closeHttpClient = true,
        )
        try {
            block(client)
        } finally {
            client.close()
            http.close()
        }
    }
}
