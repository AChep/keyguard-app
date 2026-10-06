package com.artemchep.keyguard.util.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.utils.dropCompressionHeaders
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Responses of real servers that differ from the common AWS behavior. */
class KtorS3ClientEdgeCaseTest {
    private val clients = mutableListOf<KtorS3Client>()

    @AfterTest
    fun closeClients() = runTest {
        clients.forEach { it.close() }
    }

    @Test
    fun `head maps errors that only headers carry`() = runTest {
        val missingBucket = client {
            respond("", HttpStatusCode.NotFound, headersOf("x-minio-error-code", "NoSuchBucket"))
        }
        val missingKey = client {
            respond("", HttpStatusCode.NotFound, headersOf("x-minio-error-code", "NoSuchKey"))
        }
        val badSignature = client {
            respond("", HttpStatusCode.Forbidden, headersOf("x-minio-error-code", "SignatureDoesNotMatch"))
        }
        val wrongRegion = client {
            respond("", HttpStatusCode.BadRequest, headersOf("x-amz-bucket-region", "eu-west-1"))
        }
        val badRequest = client {
            respond("", HttpStatusCode.BadRequest, headersOf("x-amz-bucket-region", "us-east-1"))
        }

        assertFailsWith<S3Exception.BucketNotFound> { missingBucket.headObject("a.zip") }
        assertNull(missingKey.headObject("a.zip"))
        assertFailsWith<S3Exception.AuthenticationFailed> { badSignature.headObject("a.zip") }
        val e = assertFailsWith<S3Exception.WrongRegion> { wrongRegion.headObject("a.zip") }
        assertEquals("eu-west-1", e.expectedRegion)
        assertFailsWith<S3Exception.Protocol> { badRequest.headObject("a.zip") }
    }

    @Test
    fun `get accepts an ignored range only when the object starts with it`() = runTest {
        val ignored = client {
            respond("payload", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "7"))
        }
        val truncated = client {
            respond("pay", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "7"))
        }

        val reads = listOf(
            S3ByteRange(0) to "payload",
            S3ByteRange(0, 2) to "pa",
            S3ByteRange(0, 100) to "payload",
        )
        for ((range, expected) in reads) {
            assertEquals(expected, ignored.getObject("a", range).use { it.readByteArray().decodeToString() }, "$range")
        }
        val e = assertFailsWith<S3Exception.Protocol> { ignored.getObject("a", S3ByteRange(1, 2)) }
        assertTrue(!e.retryable)
        assertFailsWith<S3Exception.Protocol> {
            truncated.getObject("a", S3ByteRange(0, 5)).use { it.readByteArray() }
        }
    }

    @OptIn(InternalAPI::class)
    @Test
    fun `get fails when the engine decoded a stored content encoding`() = runTest {
        // Emulates Darwin, which decodes the body and drops the headers.
        fun client(encoding: String) = client { request ->
            val headers = HeadersBuilder().apply {
                append(HttpHeaders.ContentEncoding, encoding)
                append(HttpHeaders.ContentLength, "3")
                dropCompressionHeaders(request.method, request.attributes)
            }.build()
            respond("decoded", HttpStatusCode.OK, headers)
        }

        val e = assertFailsWith<S3Exception.Protocol> { client("gzip").getObject("a") }
        assertTrue(!e.retryable)
        assertEquals("decoded", client("identity").getObject("a").use { it.readByteArray().decodeToString() })
    }

    @Test
    fun `put rethrows a failure of the body during the upload`() = runTest {
        for (endpoint in listOf("http://example.com", "https://example.com")) {
            val failure = IOException("local file is gone")
            val client = client(endpoint = endpoint) { request ->
                // Engines report a failed body as a transport failure.
                try {
                    (request.body as OutgoingContent.WriteChannelContent).writeTo(ByteChannel())
                } catch (e: Exception) {
                    throw IOException("broken pipe", e)
                }
                respond("", HttpStatusCode.OK)
            }
            var passes = 0

            val e = assertFailsWith<IOException>(endpoint) {
                client.putObject("a.zip", contentLength = 1) { sink ->
                    passes += 1
                    if (passes > 1) throw failure
                    sink.write(byteArrayOf(1))
                }
            }
            assertSame(failure, e, endpoint)
        }
    }

    @Test
    fun `put cancels an upload that waits for the rest of a failed body`() = runTest {
        val failure = IOException("local file is gone")
        var passes = 0
        // Emulates Darwin, which waits for the declared length after the
        // body stops instead of failing the request.
        val client = client { request ->
            try {
                (request.body as OutgoingContent.WriteChannelContent).writeTo(ByteChannel())
            } catch (_: Exception) {
                awaitCancellation()
            }
            respond("", HttpStatusCode.OK)
        }

        val e = assertFailsWith<IOException> {
            client.putObject("a.zip", contentLength = 1) { sink ->
                passes += 1
                if (passes > 1) throw failure
                sink.write(byteArrayOf(1))
            }
        }
        assertSame(failure, e)
    }

    @Test
    fun `replace of a deleted object is a failed precondition`() = runTest {
        val noKey = client { respond(errorXml("NoSuchKey"), HttpStatusCode.NotFound) }
        val noKeyWithoutBody = client { respond("", HttpStatusCode.NotFound) }
        val noBucket = client { respond(errorXml("NoSuchBucket"), HttpStatusCode.NotFound) }
        val ifMatch = S3WritePrecondition.IfMatch("\"v1\"")

        val e = assertFailsWith<S3Exception.PreconditionFailed> { noKey.putObject("a.kdbx", byteArrayOf(1), ifMatch) }
        assertEquals(404, e.statusCode)
        assertEquals("NoSuchKey", e.errorCode)
        assertFailsWith<S3Exception.PreconditionFailed> {
            noKeyWithoutBody.putObject("a.kdbx", byteArrayOf(1), ifMatch)
        }
        assertFailsWith<S3Exception.BucketNotFound> { noBucket.putObject("a.kdbx", byteArrayOf(1), ifMatch) }
        assertFailsWith<S3Exception.NotFound> { noKey.putObject("a.kdbx", byteArrayOf(1)) }
    }

    @Test
    fun `list continues a version 1 listing after its last key`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { request ->
            requests += request
            val page = if (request.url.parameters["marker"] == null) {
                "<Contents><Key>a%2B1</Key></Contents><Contents><Key>b+2</Key></Contents>" +
                    "<IsTruncated>true</IsTruncated>"
            } else {
                "<Contents><Key>c</Key></Contents><IsTruncated>false</IsTruncated>"
            }
            respond(
                "<ListBucketResult><EncodingType>url</EncodingType><Marker></Marker>$page</ListBucketResult>",
                HttpStatusCode.OK,
            )
        }

        val first = client.listObjects()
        val token = assertNotNull(first.nextContinuationToken)
        val second = client.listObjects(continuationToken = token)

        assertEquals(listOf("a+1", "b 2"), first.objects.map { it.key })
        assertEquals(listOf("c"), second.objects.map { it.key })
        assertNull(second.nextContinuationToken)
        assertEquals("b 2", requests[1].url.parameters["marker"])
        assertNull(requests[1].url.parameters["continuation-token"])
    }

    private fun client(
        endpoint: String = "https://example.com",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorS3Client = KtorS3Client(
        httpClient = HttpClient(MockEngine(handler)) {
            followRedirects = false
        },
        config = testS3Config(endpoint),
        closeHttpClient = true,
    ).also { clients += it }

    private fun errorXml(
        code: String,
    ): String = "<Error><Code>$code</Code><Message>message</Message></Error>"
}
