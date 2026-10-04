package com.artemchep.keyguard.util.s3

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class S3ClientContractTest {
    @Test
    fun `invalid arguments do not issue requests or invoke upload bodies`() = runTest {
        withS3Client(handler = { error("Invalid input reached the network") }) { client ->
            for (key in listOf("", "a/../b", "\uD800", "\uDC00", "é".repeat(513))) {
                assertFailsWith<IllegalArgumentException> { client.headObject(key) }
                assertFailsWith<IllegalArgumentException> { client.getObject(key) }
                assertFailsWith<IllegalArgumentException> { client.deleteObject(key) }
                assertFailsWith<IllegalArgumentException> { client.putObject(key, 0) { error("Body called") } }
            }
            for (size in listOf(-1L, 5L * 1024 * 1024 * 1024 + 1)) {
                assertFailsWith<IllegalArgumentException> { client.putObject("key", size) { error("Body called") } }
            }
            for (maxKeys in listOf(-1, 1001)) {
                assertFailsWith<IllegalArgumentException> { client.listObjects(maxKeys = maxKeys) }
            }
            assertFailsWith<IllegalArgumentException> { client.getObject("key", S3ByteRange(Long.MAX_VALUE, 2)) }
        }
    }

    @Test
    fun `list tokens stay opaque while query values are encoded once`() = runTest {
        val token = "token%2F+/%==&"
        withS3Client(handler = { request ->
            assertEquals(token, request.url.parameters["continuation-token"])
            assertEquals("p+%/😀", request.url.parameters["prefix"])
            assertEquals("1000", request.url.parameters["max-keys"])
            respond(
                "<ListBucketResult><IsTruncated>true</IsTruncated>" +
                    "<NextContinuationToken>next%2F+/%==&amp;</NextContinuationToken></ListBucketResult>",
            )
        }) { client ->
            assertEquals(
                "next%2F+/%==&",
                client.listObjects(prefix = "p+%/😀", continuationToken = token, maxKeys = 1000).nextContinuationToken,
            )
        }
    }

    @Test
    fun `last page ignores a stray continuation token and default options are omitted`() = runTest {
        withS3Client(handler = { request ->
            assertEquals(setOf("list-type", "encoding-type"), request.url.parameters.names())
            respond(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<NextContinuationToken>stray</NextContinuationToken></ListBucketResult>",
            )
        }) { client ->
            assertNull(client.listObjects().nextContinuationToken)
        }
    }

    @Test
    fun `empty and chunked uploads produce the declared bytes on every pass`() = runTest {
        // The body is read an extra time to hash it.
        for ((endpoint, expectedPasses) in listOf("http://example.com" to 2, "https://example.com" to 2)) {
            for (size in listOf(0, 1, 65535, 65536, 65537, 150000)) {
                val bytes = ByteArray(size) { (it % 251).toByte() }
                var passes = 0
                withS3Client(config = testS3Config(endpoint), handler = { request ->
                    assertEquals(size.toLong(), request.body.contentLength)
                    assertContentEquals(bytes, request.body.toByteArray())
                    respond("", headers = headersOf(HttpHeaders.ETag, "\"etag\""))
                }) { client ->
                    val result = client.putObject("key", size.toLong()) { sink ->
                        passes++
                        for (start in bytes.indices step 137) {
                            sink.write(bytes, start, minOf(start + 137, size))
                        }
                    }
                    assertEquals(expectedPasses, passes, endpoint)
                    assertEquals(size.toLong(), result.size)
                }
            }
        }
    }

    @Test
    fun `hashing failures and cancellation stop before upload`() = runTest {
        for (endpoint in listOf("http://example.com", "https://example.com")) {
            withS3Client(
                config = testS3Config(endpoint),
                handler = { error("Hashing failure reached the network") },
            ) { client ->
                for (actual in listOf(0, 2)) {
                    assertFailsWith<IllegalStateException> {
                        client.putObject("key", 1) { it.write(ByteArray(actual)) }
                    }
                }
                val failure = IllegalStateException("producer failed")
                val e = assertFailsWith<IllegalStateException> { client.putObject("key", 1) { throw failure } }
                assertSame(failure, e)
                assertFailsWith<CancellationException> {
                    client.putObject("key", 1) { throw CancellationException("cancelled") }
                }
            }
        }
    }

    @Test
    fun `unsupported conditional writes never issue HEAD or unconditional PUT`() = runTest {
        val conditions = listOf(S3WritePrecondition.IfNoneMatch, S3WritePrecondition.IfMatch("\"etag\""))
        for (status in listOf(HttpStatusCode.NotImplemented, HttpStatusCode.BadRequest)) {
            for (condition in conditions) {
                val methods = mutableListOf<HttpMethod>()
                var stored = byteArrayOf(9)
                withS3Client(handler = { request ->
                    methods += request.method
                    assertEquals(HttpMethod.Put, request.method)
                    val header = if (condition == S3WritePrecondition.IfNoneMatch) {
                        request.headers[HttpHeaders.IfNoneMatch]
                    } else {
                        request.headers[HttpHeaders.IfMatch]
                    }
                    if (header == null) {
                        stored = request.body.toByteArray()
                        respond("", headers = headersOf(HttpHeaders.ETag, "\"overwritten\""))
                    } else {
                        respond("<Error><Code>NotImplemented</Code></Error>", status)
                    }
                }) { client ->
                    // Subsequent calls must retain their conditions as well.
                    repeat(2) {
                        assertFailsWith<S3Exception.Protocol> { client.putObject("key", byteArrayOf(1), condition) }
                    }
                    assertEquals(listOf(HttpMethod.Put, HttpMethod.Put), methods)
                    assertContentEquals(byteArrayOf(9), stored)
                }
            }
        }
    }

    @Test
    fun `zero max keys reaches the server and returns an empty page`() = runTest {
        withS3Client(handler = { request ->
            assertEquals("0", request.url.parameters["max-keys"])
            respond("<ListBucketResult><IsTruncated>false</IsTruncated><KeyCount>0</KeyCount></ListBucketResult>")
        }) { client ->
            val page = client.listObjects(maxKeys = 0)
            assertEquals(emptyList(), page.objects)
            assertNull(page.nextContinuationToken)
        }
    }

    @Test
    fun `clock rejection without a server time cannot be retried`() = runTest {
        var requests = 0
        withS3Client(handler = {
            requests++
            respond("<Error><Code>RequestTimeTooSkewed</Code></Error>", HttpStatusCode.Forbidden)
        }) { client ->
            assertFailsWith<S3Exception.ClockSkew> { client.deleteObject("key") }
            assertEquals(1, requests)
        }
    }
}
