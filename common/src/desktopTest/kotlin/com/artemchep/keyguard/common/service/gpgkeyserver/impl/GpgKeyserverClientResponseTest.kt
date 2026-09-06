package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PRIMARY_FINGERPRINT
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgKeyserverClientResponseTest {
    @Test
    fun `404 is not found without parsing the response`() = runTest {
        for (config in keyserverResponseConfigs) {
            keyserverResponseHttpClient(status = HttpStatusCode.NotFound).use { http ->
                val client = GpgKeyserverClientImpl(http, unexpectedParser)

                assertNull(client.getByFingerprint(fingerprint, config).bind())
                assertTrue(client.getByEmail("alice@example.test", config).bind().isEmpty())
            }
        }
    }

    @Test
    fun `non-404 HTTP failures are not treated as missing certificates`() = runTest {
        val statuses = listOf(HttpStatusCode.BadRequest, HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway)
        for (config in keyserverResponseConfigs) {
            for (status in statuses) {
                keyserverResponseHttpClient(status = status).use { http ->
                    val client = GpgKeyserverClientImpl(http, unexpectedParser)

                    val failure = assertFailsWith<HttpException> {
                        client.getByFingerprint(fingerprint, config).bind()
                    }
                    assertEquals(status, failure.statusCode)
                }
            }
        }
    }

    @Test
    fun `unusable certificate responses fail instead of returning not found`() = runTest {
        val results = listOf(
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty),
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed),
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.UnsupportedKeyVersion),
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.MultipleCertificates),
            GpgPublicKeyParseResult.Success(emptyList()),
            GpgPublicKeyParseResult.Success(emptyList(), skippedCertificates = 1),
        )
        for (config in keyserverResponseConfigs) {
            for (result in results) {
                keyserverResponseHttpClient().use { http ->
                    val client = GpgKeyserverClientImpl(http, keyserverResponseParser(result))

                    assertFailsWith<IllegalStateException>("${config.protocol}: $result") {
                        client.getByFingerprint(fingerprint, config).bind()
                    }
                    if (config.protocol == GpgKeyserverConfig.Protocol.VKS) {
                        assertFailsWith<IllegalStateException> {
                            client.getByEmail("alice@example.test", config).bind()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an unsupported parser remains a failure`() = runTest {
        val parser = keyserverResponseParser(GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Unsupported))
        for (config in keyserverResponseConfigs) {
            keyserverResponseHttpClient().use { http ->
                assertFailsWith<UnsupportedOperationException> {
                    GpgKeyserverClientImpl(http, parser).getByFingerprint(fingerprint, config).bind()
                }
            }
        }
    }

    @Test
    fun `native parsing rejects empty malformed and mismatched responses`() = runTest {
        for (config in keyserverResponseConfigs) {
            for (body in listOf("", "not an OpenPGP certificate", GPG_TEST_CV25519_PUBLIC_KEY)) {
                keyserverResponseHttpClient(body).use { http ->
                    val client = GpgKeyserverClientImpl(http, NativeGpgPublicKeyParser)

                    assertFailsWith<IllegalStateException> {
                        client.getByFingerprint("A".repeat(40), config).bind()
                    }
                }
            }
        }
    }

    @Test
    fun `a matching certificate is selected from a multi-certificate response`() = runTest {
        val key = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(GPG_TEST_CV25519_PUBLIC_KEY),
        ).keys.single()
        val parser = keyserverResponseParser(
            GpgPublicKeyParseResult.Success(
                listOf(key.copy(fingerprint = "A".repeat(40)), key),
                skippedCertificates = 1,
            ),
        )
        for (config in keyserverResponseConfigs) {
            keyserverResponseHttpClient().use { http ->
                val client = GpgKeyserverClientImpl(http, parser)
                val formattedFingerprint = fingerprint.lowercase().chunked(4).joinToString(" ")

                val result = client.getByFingerprint(formattedFingerprint, config).bind()

                assertEquals(fingerprint, result?.fingerprint)
                assertEquals(key.publicKeyArmored, result?.publicKeyArmored)
                assertEquals(config, result?.sourceKeyserverConfig)
            }
        }
    }

    @Test
    fun `parser cancellation and fatal errors propagate`() = runTest {
        for (config in keyserverResponseConfigs) {
            for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
                val parser = object : GpgPublicKeyParser {
                    override fun parse(armored: String): GpgPublicKeyParseResult = throw failure
                }
                keyserverResponseHttpClient().use { http ->
                    val client = GpgKeyserverClientImpl(http, parser)
                    val thrown = assertFailsWith<Throwable> {
                        client.getByFingerprint(fingerprint, config).bind()
                    }
                    assertEquals(failure::class, thrown::class)
                    assertEquals(failure.message, thrown.message)
                }
            }
        }
    }

    @Test
    fun `an empty HKP index is still a valid search result`() = runTest {
        val config = keyserverResponseConfigs.single { it.protocol == GpgKeyserverConfig.Protocol.HKP }
        keyserverResponseHttpClient("info:1:0\n").use { http ->
            val client = GpgKeyserverClientImpl(http, unexpectedParser)

            assertTrue(client.search(SearchGpgPublicKeyRequest("Alice"), config).bind().isEmpty())
            assertTrue(client.getByEmail("alice@example.test", config).bind().isEmpty())
        }
    }

    private companion object {
        const val fingerprint = GPG_TEST_CV25519_PRIMARY_FINGERPRINT
        val unexpectedParser = object : GpgPublicKeyParser {
            override fun parse(armored: String): GpgPublicKeyParseResult = error("Parser must not be called")
        }
    }
}

internal val keyserverResponseConfigs = GpgKeyserverConfig.Protocol.entries.map { protocol ->
    GpgKeyserverConfig(url = "https://keys.example.test", protocol = protocol)
}

internal fun keyserverResponseHttpClient(
    body: String = "response",
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpClient = HttpClient(MockEngine { respond(content = body, status = status) })

internal fun keyserverResponseParser(
    result: GpgPublicKeyParseResult,
): GpgPublicKeyParser = object : GpgPublicKeyParser {
    override fun parse(armored: String) = result
}
