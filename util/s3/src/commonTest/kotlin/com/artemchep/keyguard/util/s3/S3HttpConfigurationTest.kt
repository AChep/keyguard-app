package com.artemchep.keyguard.util.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3HttpConfigurationTest {
    @Test
    fun `clients that follow redirects cache or decompress responses are rejected`() {
        val engine = MockEngine { respond("") }
        val redirecting = HttpClient(engine)
        val caching = HttpClient(engine) {
            followRedirects = false
            install(HttpCache)
        }
        val decompressing = HttpClient(engine) {
            followRedirects = false
            install(ContentEncoding) { gzip() }
        }
        try {
            assertFailsWith<IllegalArgumentException> { KtorS3Client(redirecting, testS3Config()) }
            assertFailsWith<IllegalArgumentException> { KtorS3Client(caching, testS3Config()) }
            assertFailsWith<IllegalArgumentException> { KtorS3Client(decompressing, testS3Config()) }
        } finally {
            redirecting.close()
            caching.close()
            decompressing.close()
            engine.close()
        }
    }

    @Test
    fun `an S3 client on a shared engine leaves the other client usable`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "example.com") {
                respond(
                    "",
                    HttpStatusCode.TemporaryRedirect,
                    headersOf(HttpHeaders.Location, "https://other.example.com/key"),
                )
            } else {
                respond("shared client still follows redirects")
            }
        }
        val shared = HttpClient(engine)
        val s3 = KtorS3Client(
            httpClient = HttpClient(engine) { followRedirects = false },
            config = testS3Config(),
            closeHttpClient = true,
        )
        try {
            // Without a region, the redirect does not come from S3.
            assertFailsWith<S3Exception.Protocol> { s3.headObject("key") }
            s3.close()
            assertEquals("other.example.com", shared.get("https://example.com/key").call.request.url.host)
        } finally {
            s3.close()
            shared.close()
            engine.close()
        }
    }

    @Test
    fun `shared success validation does not hide typed S3 errors`() = runTest {
        val http = HttpClient(MockEngine {
            respond("<Error><Code>AccessDenied</Code></Error>", HttpStatusCode.Forbidden)
        }) {
            followRedirects = false
            expectSuccess = true
        }
        val s3 = KtorS3Client(http, testS3Config())
        try {
            assertFailsWith<S3Exception.PermissionDenied> { s3.getObject("key") }
            assertFailsWith<S3Exception.PermissionDenied> { s3.listObjects() }
            assertFailsWith<ClientRequestException> { http.get("https://example.com/key") }
        } finally {
            s3.close()
            http.close()
        }
    }
}
