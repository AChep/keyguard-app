package com.artemchep.keyguard.util.s3

import com.artemchep.keyguard.util.foundation.crypto.createHmacSha256
import com.artemchep.keyguard.util.foundation.crypto.createMd5
import com.artemchep.keyguard.util.foundation.crypto.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSecure
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class KtorS3ClientTest {
    private val clients = mutableListOf<KtorS3Client>()

    @AfterTest
    fun closeClients() = runTest {
        clients.forEach { it.close() }
    }

    private val credentials = S3Credentials(
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "secret/key+1",
    )

    @Test
    fun `soap object operations sign the AWS path-style target`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client(pathStyle = false, endpoint = "https://s3.us-east-1.amazonaws.com") { request ->
            methods += request.method
            assertEquals("s3.us-east-1.amazonaws.com", request.url.host)
            assertEquals("/bucket/soap", request.url.encodedPath)
            verifySignature(request, request.body.toByteArray())
            respond("", HttpStatusCode.OK)
        }

        client.headObject("soap")
        client.getObject("soap").close()
        client.putObject("soap", byteArrayOf(1, 2, 3))
        client.deleteObject("soap")

        assertEquals(listOf(HttpMethod.Head, HttpMethod.Get, HttpMethod.Put, HttpMethod.Delete), methods)
    }

    @Test
    fun `head returns metadata from a signed path-style request`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { request ->
            requests += request
            verifySignature(request)
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("42"),
                    HttpHeaders.ETag to listOf("\"abc\""),
                    HttpHeaders.LastModified to listOf("Tue, 15 Nov 1994 12:45:26 GMT"),
                ),
            )
        }

        val result = assertNotNull(client.headObject("dir/file #+.kdbx"))

        val request = requests.single()
        assertEquals(HttpMethod.Head, request.method)
        assertEquals("https://example.com/bucket/dir/file%20%23%2B.kdbx", request.url.toString())
        assertNull(request.headers[HttpHeaders.CacheControl])
        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
        assertEquals(42L, result.size)
        assertEquals("\"abc\"", result.etag)
        assertEquals(Instant.fromEpochSeconds(784903526L), result.lastModified)
    }

    @Test
    fun `head of a missing object returns null`() = runTest {
        val client = client { respond("", HttpStatusCode.NotFound) }

        assertNull(client.headObject("missing.kdbx"))
    }

    @Test
    fun `virtual-hosted requests put the bucket in the host`() = runTest {
        var url: String? = null
        val client = client(pathStyle = false) { request ->
            url = request.url.toString()
            verifySignature(request)
            respond("", HttpStatusCode.NotFound)
        }

        client.headObject("a.kdbx")

        assertEquals("https://bucket.example.com/a.kdbx", url)
    }

    @Test
    fun `get streams the object`() = runTest {
        val client = client { request ->
            verifySignature(request)
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = "payload",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "7"),
            )
        }

        val bytes = client.getObject("a.kdbx").use { it.readByteArray() }

        assertEquals("payload", bytes.decodeToString())
    }

    @Test
    fun `get signs and validates a byte range`() = runTest {
        val client = client { request ->
            verifySignature(request)
            assertEquals("bytes=2-4", request.headers[HttpHeaders.Range])
            respond(
                content = "yLo",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentRange to listOf("bytes 2-4/7"),
                    HttpHeaders.ContentLength to listOf("3"),
                ),
            )
        }

        val bytes = client.getObject("a.kdbx", S3ByteRange(offset = 2, length = 3)).use { it.readByteArray() }

        assertEquals("yLo", bytes.decodeToString())
    }

    @Test
    fun `get reports an unsatisfiable range as invalid`() = runTest {
        val unsatisfiable = client {
            respond(errorXml("InvalidRange"), HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val shortened = client {
            respond(
                content = "load",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 3-6/7"),
            )
        }

        assertFailsWith<S3Exception.InvalidRange> { unsatisfiable.getObject("a", S3ByteRange(100)) }
        assertEquals("load", shortened.getObject("a", S3ByteRange(3, 10)).use { it.readByteArray().decodeToString() })
    }

    @Test
    fun `get reports a truncated body as a retryable protocol error`() = runTest {
        val client = client {
            respond("pay", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "7"))
        }

        val e = assertFailsWith<S3Exception.Protocol> {
            client.getObject("a.kdbx").use { it.readByteArray() }
        }
        assertTrue(e.retryable)
    }

    @Test
    fun `get maps missing object and missing bucket`() = runTest {
        val noKey = client { respond(errorXml("NoSuchKey"), HttpStatusCode.NotFound) }
        val noBucket = client { respond(errorXml("NoSuchBucket"), HttpStatusCode.NotFound) }

        assertFailsWith<S3Exception.NotFound> { noKey.getObject("a") }
        assertFailsWith<S3Exception.BucketNotFound> { noBucket.getObject("a") }
    }

    @Test
    fun `put over http signs the payload hash and sends a content length`() = runTest {
        val payload = "hello world".encodeToByteArray()
        var bodyCalls = 0
        val client = client(endpoint = "http://example.com") { request ->
            val body = request.body.toByteArray()
            verifySignature(request, body)
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(sha256(payload).toHexString(), request.headers["x-amz-content-sha256"])
            assertEquals(11L, request.body.contentLength)
            assertEquals(md5Base64(payload), request.headers["Content-MD5"])
            assertNull(request.headers[HttpHeaders.IfNoneMatch])
            assertContentEquals(payload, body)
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"new\""))
        }

        val result = client.putObject("a.zip", payload.size.toLong()) { sink ->
            bodyCalls += 1
            sink.write(payload)
        }

        assertEquals("\"new\"", result.etag)
        assertEquals(11L, result.size)
        assertEquals(2, bodyCalls)
    }

    @Test
    fun `put over https leaves the payload unsigned but signs its MD5`() = runTest {
        val payload = "hello world".encodeToByteArray()
        var bodyCalls = 0
        val client = client { request ->
            val body = request.body.toByteArray()
            verifySignature(request, body)
            assertEquals("UNSIGNED-PAYLOAD", request.headers["x-amz-content-sha256"])
            // Buckets with Object Lock retention require it.
            assertEquals("XrY7u+Ae7tCTyyK7j1rNww==", request.headers["Content-MD5"])
            assertTrue("content-md5" in request.headers[HttpHeaders.Authorization].orEmpty())
            assertContentEquals(payload, body)
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"new\""))
        }

        client.putObject("a.zip", payload.size.toLong()) { sink ->
            bodyCalls += 1
            sink.write(payload)
        }

        assertEquals(2, bodyCalls)
    }

    @Test
    fun `put rejects a body that does not match the content length before uploading`() = runTest {
        for (endpoint in listOf("http://example.com", "https://example.com")) {
            var requests = 0
            val client = client(endpoint = endpoint) {
                requests += 1
                respond("", HttpStatusCode.OK)
            }

            assertFailsWith<IllegalStateException>(endpoint) {
                client.putObject("a.zip", contentLength = 3) { sink -> sink.write(byteArrayOf(1)) }
            }
            assertEquals(0, requests, endpoint)
        }
    }

    @Test
    fun `put is not retried automatically`() = runTest {
        var puts = 0
        val client = client(
            configure = {
                install(HttpRequestRetry) {
                    maxRetries = 3
                    retryIf { _, response -> response.status.value >= 500 }
                    constantDelay(millis = 1)
                }
            },
        ) {
            puts += 1
            respond(errorXml("SlowDown"), HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<S3Exception.Transient> {
            client.putObject("a.zip", byteArrayOf(1, 2, 3))
        }
        assertEquals(1, puts)
    }

    @Test
    fun `create sends If-None-Match without a HEAD and maps a 412 to already exists`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client { request ->
            methods += request.method
            verifySignature(request, request.body.toByteArray())
            assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
            respond(errorXml("PreconditionFailed"), HttpStatusCode.PreconditionFailed)
        }

        assertFailsWith<S3Exception.AlreadyExists> {
            client.putObject("a.zip", byteArrayOf(1), S3WritePrecondition.IfNoneMatch)
        }
        assertEquals(listOf(HttpMethod.Put), methods)
    }

    @Test
    fun `replace sends If-Match without a HEAD and maps a 412 to a failed precondition`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client { request ->
            methods += request.method
            verifySignature(request, request.body.toByteArray())
            assertEquals("\"v1\"", request.headers[HttpHeaders.IfMatch])
            respond(errorXml("PreconditionFailed"), HttpStatusCode.PreconditionFailed)
        }

        assertFailsWith<S3Exception.PreconditionFailed> {
            client.putObject("a.kdbx", byteArrayOf(1), S3WritePrecondition.IfMatch("\"v1\""))
        }
        assertEquals(listOf(HttpMethod.Put), methods)
    }

    @Test
    fun `weak preconditions are sent unchanged and never matched locally`() = runTest {
        val requests = mutableListOf<HttpMethod>()
        val client = client { request ->
            requests += request.method
            assertEquals("W/\"v1\"", request.headers[HttpHeaders.IfMatch])
            respond(errorXml("PreconditionFailed"), HttpStatusCode.PreconditionFailed)
        }

        assertFailsWith<S3Exception.PreconditionFailed> {
            client.putObject("a.kdbx", byteArrayOf(1), S3WritePrecondition.IfMatch("W/\"v1\""))
        }
        assertEquals(listOf(HttpMethod.Put), requests)
    }

    @Test
    fun `put without an ETag does not borrow metadata from a later version`() = runTest {
        val requests = mutableListOf<HttpMethod>()
        val client = client { request ->
            requests += request.method
            when (request.method) {
                HttpMethod.Head -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"later\""))
                else -> respond("", HttpStatusCode.OK)
            }
        }

        assertNull(client.putObject("a.zip", byteArrayOf(1)).etag)
        assertEquals(listOf(HttpMethod.Put), requests)
    }

    @Test
    fun `list parses a page and decodes url-encoded keys`() = runTest {
        val client = client { request ->
            verifySignature(request)
            assertEquals("/bucket", request.url.encodedPath)
            val parameters = request.url.parameters
            assertEquals("2", parameters["list-type"])
            assertEquals("url", parameters["encoding-type"])
            assertEquals("dir a/", parameters["prefix"])
            assertEquals("/", parameters["delimiter"])
            assertEquals("t+1", parameters["continuation-token"])
            assertEquals("10", parameters["max-keys"])
            respond(
                content = """
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <EncodingType>url</EncodingType>
                      <IsTruncated>true</IsTruncated>
                      <NextContinuationToken>t+2</NextContinuationToken>
                      <Contents><Key>dir+a/file%2B1.kdbx</Key><Size>3</Size><ETag>"e"</ETag></Contents>
                      <CommonPrefixes><Prefix>dir+a/sub/</Prefix></CommonPrefixes>
                    </ListBucketResult>
                """.trimIndent(),
                status = HttpStatusCode.OK,
            )
        }

        val page = client.listObjects(prefix = "dir a/", delimiter = "/", continuationToken = "t+1", maxKeys = 10)

        assertEquals(listOf("dir a/file+1.kdbx"), page.objects.map { it.key })
        assertEquals(listOf("dir a/sub/"), page.commonPrefixes)
        assertEquals("t+2", page.nextContinuationToken)
    }

    @Test
    fun `list rejects a truncated page without a new continuation token`() = runTest {
        val noToken = client {
            respond("<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>", HttpStatusCode.OK)
        }
        val sameToken = client {
            respond(
                "<ListBucketResult><IsTruncated>true</IsTruncated>" +
                    "<NextContinuationToken>t</NextContinuationToken></ListBucketResult>",
                HttpStatusCode.OK,
            )
        }

        assertFailsWith<S3Exception.Protocol> { noToken.listObjects() }
        assertFailsWith<S3Exception.Protocol> { sameToken.listObjects(continuationToken = "t") }
    }

    @Test
    fun `list reports unparseable XML as a protocol error`() = runTest {
        val client = client { respond("<html>", HttpStatusCode.OK) }

        assertFailsWith<S3Exception.Protocol> { client.listObjects() }
    }

    @Test
    fun `list reports an invalid url-encoded key as a permanent protocol error`() = runTest {
        val client = client {
            respond(
                "<ListBucketResult><IsTruncated>false</IsTruncated><EncodingType>url</EncodingType>" +
                    "<Contents><Key>a%zz</Key></Contents></ListBucketResult>",
                HttpStatusCode.OK,
            )
        }

        val e = assertFailsWith<S3Exception.Protocol> { client.listObjects() }
        assertTrue(!e.retryable)
    }

    @Test
    fun `delete treats a missing object as success but not a missing bucket`() = runTest {
        val deleted = client { request ->
            verifySignature(request)
            respond("", HttpStatusCode.NoContent)
        }
        val missingKey = client { respond(errorXml("NoSuchKey"), HttpStatusCode.NotFound) }
        val missingKeyWithoutBody = client { respond("", HttpStatusCode.NotFound) }
        val missingBucket = client { respond(errorXml("NoSuchBucket"), HttpStatusCode.NotFound) }
        val proxyPage = client { respond("<html>Not Found</html>", HttpStatusCode.NotFound) }

        deleted.deleteObject("a.zip")
        missingKey.deleteObject("a.zip")
        missingKeyWithoutBody.deleteObject("a.zip")
        assertFailsWith<S3Exception.BucketNotFound> { missingBucket.deleteObject("a.zip") }
        assertFailsWith<S3Exception.NotFound> { proxyPage.deleteObject("a.zip") }
    }

    @Test
    fun `error responses map to exceptions`() = runTest {
        data class Case(val status: HttpStatusCode, val code: String?, val check: (S3Exception) -> Boolean)

        val cases = listOf(
            Case(HttpStatusCode.Forbidden, "InvalidAccessKeyId") { it is S3Exception.AuthenticationFailed },
            Case(HttpStatusCode.Forbidden, "SignatureDoesNotMatch") { it is S3Exception.AuthenticationFailed },
            Case(HttpStatusCode.Forbidden, "AccessDenied") { it is S3Exception.PermissionDenied },
            Case(HttpStatusCode.Forbidden, null) { it is S3Exception.PermissionDenied },
            Case(HttpStatusCode.MovedPermanently, "PermanentRedirect") {
                it is S3Exception.WrongRegion && it.expectedRegion == "eu-west-1"
            },
            Case(HttpStatusCode.BadRequest, "AuthorizationHeaderMalformed") { it is S3Exception.WrongRegion },
            Case(HttpStatusCode.Conflict, "OperationAborted") { it is S3Exception.Transient },
            Case(HttpStatusCode.Conflict, "ObjectParentIsFile") { it is S3Exception.Protocol && !it.retryable },
            Case(HttpStatusCode.Forbidden, "InvalidObjectState") { it is S3Exception.Protocol && !it.retryable },
            Case(HttpStatusCode.BadRequest, "Throttling") { it is S3Exception.Transient },
            Case(HttpStatusCode.ServiceUnavailable, "SlowDown") { it is S3Exception.Transient && it.retryable },
            Case(HttpStatusCode.InternalServerError, null) { it is S3Exception.Transient },
            Case(HttpStatusCode.InsufficientStorage, null) { it is S3Exception.InsufficientStorage },
            Case(HttpStatusCode.Forbidden, "XMinioStorageFull") { it is S3Exception.InsufficientStorage },
            Case(HttpStatusCode.BadRequest, "EntityTooLarge") { it is S3Exception.Protocol && !it.retryable },
            Case(HttpStatusCode.NotImplemented, null) { it is S3Exception.Protocol },
        )
        cases.forEach { case ->
            val client = client {
                respond(
                    content = case.code?.let { errorXml(it, region = "eu-west-1") }.orEmpty(),
                    status = case.status,
                )
            }
            val e = assertFailsWith<S3Exception>("${case.status} ${case.code}") {
                client.getObject("a.zip")
            }
            assertTrue(case.check(e), "${case.status} ${case.code}: $e")
            assertEquals(case.code, e.errorCode)
        }
    }

    @Test
    fun `a skewed clock is corrected from the error body and the request is retried`() = runTest {
        val serverTime = Instant.parse("2030-01-01T00:00:00Z")
        val dates = mutableListOf<String?>()
        val client = client(clock = FixedClock(serverTime - 30.minutes)) { request ->
            dates += request.headers["x-amz-date"]
            if (dates.size == 1) {
                respond(
                    errorXml("RequestTimeTooSkewed", serverTime = serverTime),
                    HttpStatusCode.Forbidden,
                )
            } else {
                verifySignature(request)
                respond("", HttpStatusCode.NoContent)
            }
        }

        client.deleteObject("a.zip")

        assertEquals(listOf<String?>("20291231T233000Z", "20300101T000000Z"), dates)
    }

    @Test
    fun `a skewed clock is detected on HEAD from the Date header`() = runTest {
        var requests = 0
        val client = client(clock = FixedClock(Instant.parse("2030-01-01T00:00:00Z"))) {
            requests += 1
            if (requests == 1) {
                respond("", HttpStatusCode.Forbidden, headersOf(HttpHeaders.Date, "Tue, 01 Jan 2030 01:00:00 GMT"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }

        assertNull(client.headObject("a.zip"))
        assertEquals(2, requests)
    }

    @Test
    fun `a clock that stays skewed fails`() = runTest {
        val serverTime = Instant.parse("2030-01-01T00:00:00Z")
        val client = client {
            respond(errorXml("RequestTimeTooSkewed", serverTime = serverTime), HttpStatusCode.Forbidden)
        }

        assertFailsWith<S3Exception.ClockSkew> { client.deleteObject("a.zip") }
    }

    @Test
    fun `close only closes an owned http client`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val shared = HttpClient(engine) { followRedirects = false }
        val owned = HttpClient(engine) { followRedirects = false }

        KtorS3Client(shared, config()).close()
        KtorS3Client(owned, config(), closeHttpClient = true).close()

        assertTrue(shared.isActive())
        assertTrue(!owned.isActive())
        shared.close()
        engine.close()
    }

    private fun HttpClient.isActive(): Boolean = coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    private fun config(
        pathStyle: Boolean = true,
        endpoint: String = "https://example.com",
    ) = S3ClientConfig(
        endpoint = endpoint,
        region = "us-east-1",
        bucket = "bucket",
        credentials = credentials,
        pathStyle = pathStyle,
    )

    private fun client(
        pathStyle: Boolean = true,
        endpoint: String = "https://example.com",
        clock: Clock = Clock.System,
        configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorS3Client = KtorS3Client(
        httpClient = HttpClient(MockEngine(handler)) {
            followRedirects = false
            configure()
        },
        config = config(pathStyle, endpoint),
        closeHttpClient = true,
        clock = clock,
    ).also { clients += it }

    /**
     * Recomputes the SigV4 signature from the request as it reached the
     * engine, independently of [S3SigV4Signer].
     */
    private fun verifySignature(
        request: HttpRequestData,
        body: ByteArray = ByteArray(0),
    ) {
        val authorization = assertNotNull(request.headers[HttpHeaders.Authorization])
        val match = assertNotNull(AUTHORIZATION_REGEX.matchEntire(authorization), authorization)
        val accessKeyId = match.groupValues[1]
        val date = match.groupValues[2]
        val region = match.groupValues[3]
        val signedHeaders = match.groupValues[4]
        val signature = match.groupValues[5]
        assertEquals(credentials.accessKeyId, accessKeyId)

        val payloadHash = assertNotNull(request.headers["x-amz-content-sha256"])
        // Uploads over HTTPS do not sign their payload.
        val expectedPayloadHash = if (request.method == HttpMethod.Put && request.url.protocol.isSecure()) {
            S3SigV4Signer.UNSIGNED_PAYLOAD
        } else {
            sha256(body).toHexString()
        }
        assertEquals(expectedPayloadHash, payloadHash)

        val url = request.url
        val host = if (url.port == url.protocol.defaultPort) url.host else "${url.host}:${url.port}"
        val canonicalHeaders = signedHeaders.split(';').joinToString(separator = "") { name ->
            val value = if (name == "host") host else assertNotNull(request.headers[name], name)
            "$name:${value.trim()}\n"
        }
        val canonicalQuery = url.encodedQuery
            .split('&')
            .filter { it.isNotEmpty() }
            .map { if ('=' in it) it else "$it=" }
            .sorted()
            .joinToString(separator = "&")
        val canonicalRequest = listOf(
            request.method.value,
            url.encodedPath,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString(separator = "\n")
        val amzDate = assertNotNull(request.headers["x-amz-date"])
        val scope = "$date/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" +
            sha256(canonicalRequest.encodeToByteArray()).toHexString()
        var key = "AWS4${credentials.secretAccessKey}".encodeToByteArray()
        listOf(date, region, "s3", "aws4_request").forEach { part ->
            key = hmac(key, part.encodeToByteArray())
        }
        assertEquals(hmac(key, stringToSign.encodeToByteArray()).toHexString(), signature)
        listOf("cache-control", "user-agent", "content-type", "content-length").forEach { name ->
            assertTrue(name !in signedHeaders.split(';'), "$name must not be signed")
        }
    }

    private fun md5Base64(
        data: ByteArray,
    ): String = createMd5().run {
        update(data)
        Base64.encode(doFinal())
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = createHmacSha256(key).run {
        update(data)
        doFinal()
    }

    private fun errorXml(
        code: String,
        region: String? = null,
        serverTime: Instant? = null,
    ): String = buildString {
        append("<Error><Code>$code</Code><Message>message</Message>")
        region?.let { append("<Region>$it</Region>") }
        serverTime?.let { append("<ServerTime>$it</ServerTime>") }
        append("</Error>")
    }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        private val AUTHORIZATION_REGEX = Regex(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, " +
                "SignedHeaders=([a-z0-9;-]+), Signature=([0-9a-f]{64})",
        )
    }
}
