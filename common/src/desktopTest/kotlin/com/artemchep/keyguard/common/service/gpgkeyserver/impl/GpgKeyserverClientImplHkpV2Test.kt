package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.GpgKeyserverFingerprintMismatchException
import com.artemchep.keyguard.common.exception.GpgKeyserverUnsupportedKeyVersionException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GpgKeyserverClientImplHkpV2Test {
    private val v6Fingerprint = "FEDCBA9876543210" + "A".repeat(48)

    @Test
    fun `v2 binary certificates are parsed as armor`() = runTest {
        val certificates = ByteArray(100) { it.toByte() }
        val parser = FakeParser(
            GpgPublicKeyParseResult.Success(
                listOf(keyInfo(v6Fingerprint, userIds = emptyList(), emails = emptyList())),
            ),
        )
        val client = v2Client { respond(content = certificates) }

        val result = GpgKeyserverClientImpl(client.http, parser)
            .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
            .bind()

        assertEquals(v6Fingerprint, result?.fingerprint)
        val expected = buildString {
            appendLine("-----BEGIN PGP PUBLIC KEY BLOCK-----")
            appendLine()
            Base64.Default.encode(certificates).chunked(64).forEach(::appendLine)
            append("-----END PGP PUBLIC KEY BLOCK-----")
        }
        assertEquals(expected, parser.armoredInputs.single())
    }

    @Test
    fun `v2 binary certificates are read by the native parser`() = runTest {
        val client = v2Client { respond(content = GPG_TEST_CV25519_PUBLIC_KEY.dearmor()) }

        // The parsed key is the V4 test key, which does not have the
        // V6 fingerprint that was asked for.
        assertFailsWith<GpgKeyserverFingerprintMismatchException> {
            GpgKeyserverClientImpl(client.http, NativeGpgPublicKeyParser)
                .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
                .bind()
        }
    }

    @Test
    fun `a v2 404 means not found only on a keyserver with the v2 lookup`() = runTest {
        val supported = listOf(
            respondOptions(HttpStatusCode.OK, allow = "GET, OPTIONS"),
            respondOptions(HttpStatusCode.NoContent),
            respondOptions(HttpStatusCode.MethodNotAllowed, allow = "GET, HEAD"),
        )
        supported.forEachIndexed { index, options ->
            val client = v2Client(options)

            val result = GpgKeyserverClientImpl(client.http, FakeParser())
                .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
                .bind()

            assertNull(result, message = "$index")
            assertEquals(
                listOf(
                    HttpMethod.Get to GpgKeyserverClientImpl.ROUTE_HKP_V2_BY_FINGERPRINT,
                    HttpMethod.Options to GpgKeyserverClientImpl.ROUTE_HKP_V2_OPTIONS,
                ),
                client.requests.map { it.method to it.route },
                message = "$index",
            )
        }

        // Older keyservers have no v2 API, and cannot store V6 keys.
        val unsupported = listOf(
            respondOptions(HttpStatusCode.NotFound),
            respondOptions(HttpStatusCode.NotImplemented),
            respondOptions(HttpStatusCode.MethodNotAllowed, allow = "POST"),
        )
        unsupported.forEachIndexed { index, options ->
            val client = v2Client(options)

            assertFailsWith<GpgKeyserverUnsupportedKeyVersionException>(message = "$index") {
                GpgKeyserverClientImpl(client.http, FakeParser())
                    .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
                    .bind()
            }
        }
    }

    @Test
    fun `a v2 501 means unsupported without asking for options`() = runTest {
        val client = v2Client(
            get = { respond(content = "", status = HttpStatusCode.NotImplemented) },
            options = { error("Unexpected OPTIONS request") },
        )

        assertFailsWith<GpgKeyserverUnsupportedKeyVersionException> {
            GpgKeyserverClientImpl(client.http, FakeParser())
                .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
                .bind()
        }
        assertEquals(listOf(HttpMethod.Get), client.requests.map { it.method })
    }

    @Test
    fun `other v2 errors are not a missing key`() = runTest {
        val client = v2Client { respond(content = "", status = HttpStatusCode.BadGateway) }

        assertFailsWith<HttpException> {
            GpgKeyserverClientImpl(client.http, FakeParser())
                .getByFingerprint(v6Fingerprint, TEST_HKP_CONFIG)
                .bind()
        }
    }

    private class V2Client(
        val http: HttpClient,
        val requests: List<RecordedRequest>,
    )

    private fun v2Client(
        options: Responder = respondOptions(HttpStatusCode.OK),
        get: Responder = { respond(content = "", status = HttpStatusCode.NotFound) },
    ): V2Client {
        val requests = mutableListOf<RecordedRequest>()
        val http = HttpClient(
            MockEngine { request ->
                requests += RecordedRequest(
                    method = request.method,
                    pathSegments = request.url.segments,
                    route = request.attributes.getOrNull(routeAttribute),
                )
                when (request.method) {
                    HttpMethod.Get -> get()
                    HttpMethod.Options -> options()
                    else -> error("Unexpected ${request.method} request")
                }
            },
        )
        return V2Client(http, requests)
    }

    private fun respondOptions(
        status: HttpStatusCode,
        allow: String? = null,
    ): Responder = {
        respond(
            content = "",
            status = status,
            headers = allow
                ?.let { headersOf(HttpHeaders.Allow, it) }
                ?: headersOf(),
        )
    }

    private fun String.dearmor(): ByteArray = lines()
        .dropWhile { it.isNotEmpty() }
        .drop(1)
        .takeWhile { !it.startsWith("-----END") }
        .filterNot { it.startsWith("=") }
        .joinToString(separator = "")
        .let(Base64.Default::decode)
}

private typealias Responder = suspend MockRequestHandleScope.() -> HttpResponseData
