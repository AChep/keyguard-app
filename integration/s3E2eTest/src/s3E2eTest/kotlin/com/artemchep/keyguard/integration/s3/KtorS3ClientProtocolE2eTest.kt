package com.artemchep.keyguard.integration.s3

import com.artemchep.keyguard.util.s3.KtorS3Client
import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KtorS3ClientProtocolE2eTest {
    @Test
    fun `bounded ranges extending beyond EOF return the remaining object bytes`() = runBlocking(Dispatchers.IO) {
        withTimeout(20.seconds) {
            for ((engine, createHttp) in s3HttpEngines) {
                val payload = "abcde"
                S3HttpFixture { request, socket ->
                    val offset = request.headers.getValue("range").substringAfter("bytes=").substringBefore('-').toInt()
                    assertEquals("bytes=$offset-${offset + 99}", request.headers["range"])
                    request.verifySignature()
                    socket.respond(
                        status = 206,
                        headers = mapOf("Content-Range" to "bytes $offset-4/5"),
                        body = payload.substring(offset),
                    )
                }.use { server ->
                    server.client(createHttp()).useS3Client { client ->
                        for (offset in listOf(0, 2, 4)) {
                            val actual = client.getObject("key", S3ByteRange(offset.toLong(), 100))
                                .use { it.readByteArray().decodeToString() }
                            assertEquals(payload.substring(offset), actual, "$engine offset=$offset")
                        }
                    }
                    assertEquals(3, server.requests.size, engine)
                }
            }
        }
    }

    @Test
    fun `invalid UTF8 and XML listing bytes fail as protocol errors`() = runBlocking(Dispatchers.IO) {
        withTimeout(30.seconds) {
            val prefix = "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>".encodeToByteArray()
            val suffix = "</Key></Contents></ListBucketResult>".encodeToByteArray()
            val cases = listOf(
                "invalid continuation" to prefix + byteArrayOf(0xC3.toByte(), 0x28) + suffix,
                "isolated continuation" to prefix + byteArrayOf(0x80.toByte()) + suffix,
                "incomplete UTF8 at EOF" to prefix + byteArrayOf(0xE2.toByte(), 0x82.toByte()),
                "literal NUL" to prefix + byteArrayOf(0) + suffix,
                "surrogate reference" to prefix + "&#xD800;".encodeToByteArray() + suffix,
                "mismatched close tag" to prefix + "key</Size></Contents></ListBucketResult>".encodeToByteArray(),
            )
            for ((engine, createHttp) in s3HttpEngines) {
                for ((case, bytes) in cases) {
                    S3HttpFixture { request, socket ->
                        request.verifySignature()
                        socket.writeHeaders(headers = mapOf("Content-Length" to bytes.size.toString()))
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()
                    }.use { server ->
                        server.client(createHttp()).useS3Client { client ->
                            val error = assertFailsWith<S3Exception.Protocol>("$engine $case") { client.listObjects() }
                            assertEquals(S3Operation.List, error.operation)
                        }
                        assertEquals(1, server.requests.size, "$engine $case")
                    }
                }
            }
        }
    }

    @Test
    fun `malformed pagination never becomes an empty final page`() = runBlocking(Dispatchers.IO) {
        withTimeout(30.seconds) {
            val cases = listOf(
                "missing flag" to "",
                "empty flag" to "<IsTruncated/>",
                "invalid flag" to "<IsTruncated>maybe</IsTruncated>",
                "uppercase flag" to "<IsTruncated>TRUE</IsTruncated>",
                "missing token" to "<IsTruncated>true</IsTruncated>",
                "repeated token" to "<IsTruncated>true</IsTruncated>" +
                    "<NextContinuationToken>same</NextContinuationToken>",
            )
            for ((engine, createHttp) in s3HttpEngines) {
                for ((case, fields) in cases) {
                    S3HttpFixture { request, socket ->
                        request.verifySignature()
                        socket.respond(body = "<ListBucketResult>$fields</ListBucketResult>")
                    }.use { server ->
                        server.client(createHttp()).useS3Client { client ->
                            val error = assertFailsWith<S3Exception.Protocol>("$engine $case") {
                                client.listObjects(continuationToken = "same")
                            }
                            assertEquals(S3Operation.List, error.operation)
                        }
                        assertEquals(1, server.requests.size, "$engine $case")
                    }
                }
            }
        }
    }

    @Test
    fun `XML line ending normalization preserves referenced carriage returns`() = runBlocking(Dispatchers.IO) {
        withTimeout(20.seconds) {
            val xml = "<ListBucketResult><IsTruncated>true</IsTruncated>" +
                "<Contents><Key>a\r\nb\rc\n&#13;d<![CDATA[\r\ne\r]]></Key></Contents>" +
                "<Contents><Key>é😀\uFFFD</Key></Contents>" +
                "<CommonPrefixes><Prefix>folder\r\nnext\r</Prefix></CommonPrefixes>" +
                "<NextContinuationToken>cursor\r\nnext&#13;</NextContinuationToken></ListBucketResult>"
            for ((engine, createHttp) in s3HttpEngines) {
                S3HttpFixture { request, socket ->
                    request.verifySignature()
                    socket.respond(body = xml)
                }.use { server ->
                    server.client(createHttp()).useS3Client { client ->
                        val page = client.listObjects()
                        assertEquals(listOf("a\nb\nc\n\rd\ne\n", "é😀\uFFFD"), page.objects.map { it.key }, engine)
                        assertEquals(listOf("folder\nnext\n"), page.commonPrefixes, engine)
                        assertEquals("cursor\nnext\r", page.nextContinuationToken, engine)
                    }
                    assertEquals(1, server.requests.size, engine)
                }
            }
        }
    }

    @Test
    fun `stored gzip objects and ranges preserve their exact encoded bytes`() = runBlocking(Dispatchers.IO) {
        withTimeout(20.seconds) {
            val stored = ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { gzip -> gzip.write("Stored gzip object payload.".encodeToByteArray()) }
                output.toByteArray()
            }
            for ((engine, createHttp) in s3HttpEngines) {
                S3HttpFixture { request, socket ->
                    request.verifySignature()
                    assertEquals("identity", request.headers["accept-encoding"])
                    val range = request.headers["range"]
                    val responseBytes = if (range == null) stored else stored.copyOfRange(2, 9)
                    val headers = mapOf(
                        "Content-Encoding" to "gzip",
                        "Content-Length" to responseBytes.size.toString(),
                    ) + if (range == null) {
                        emptyMap()
                    } else {
                        assertEquals("bytes=2-8", range)
                        mapOf("Content-Range" to "bytes 2-8/${stored.size}")
                    }
                    socket.writeHeaders(status = if (range == null) 200 else 206, headers = headers)
                    socket.getOutputStream().write(responseBytes)
                    socket.getOutputStream().flush()
                }.use { server ->
                    server.client(createHttp()).useS3Client { client ->
                        assertContentEquals(stored, client.getObject("gzip").use { it.readByteArray() }, engine)
                        assertContentEquals(
                            stored.copyOfRange(2, 9),
                            client.getObject("gzip", S3ByteRange(2, 7)).use { it.readByteArray() },
                            engine,
                        )
                    }
                    assertEquals(2, server.requests.size, engine)
                }
            }
        }
    }

    @Test
    fun `get after closing the HTTP client fails promptly`() = runBlocking(Dispatchers.IO) {
        withTimeout(20.seconds) {
            for ((engine, createHttp) in s3HttpEngines) {
                S3HttpFixture { _, _ -> error("A closed client reached the network") }.use { server ->
                    val http = createHttp()
                    server.client(http).useS3Client { client ->
                        client.close()
                        http.coroutineContext[Job]!!.join()
                        val error = assertFailsWith<S3Exception.Transient>(engine) {
                            // runBlocking uses real time, including this deadline after client shutdown.
                            withTimeout(2.seconds) { client.getObject("key").close() }
                        }
                        assertEquals(S3Operation.Get, error.operation)
                    }
                    assertTrue(server.requests.isEmpty(), engine)
                }
            }
        }
    }

    private fun S3HttpFixture.client(http: HttpClient): KtorS3Client = KtorS3Client(
        httpClient = http,
        config = S3ClientConfig(
            endpoint = endpoint,
            bucket = "bucket",
            credentials = S3Credentials("test-access", "test-secret"),
        ),
        closeHttpClient = true,
    )
}
