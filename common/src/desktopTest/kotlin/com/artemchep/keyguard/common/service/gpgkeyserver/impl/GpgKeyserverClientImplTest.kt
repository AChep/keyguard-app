package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgKeyserverClientImplTest {
    @Test
    fun `both keyserver protocols preserve full v6 fingerprints in lookups`() = runTest {
        val fingerprint = "FEDCBA9876543210" + "A".repeat(48)
        for (protocol in GpgKeyserverConfig.Protocol.entries) {
            val requests = mutableListOf<RecordedRequest>()
            val parser = FakeParser(GpgPublicKeyParseResult.Success(listOf(
                keyInfo(fingerprint, userIds = emptyList(), emails = emptyList()),
            )))
            val client = recordingClient(
                requests = requests,
                response = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                contentType = ContentType.parse("application/pgp-keys"),
            )
            val result = GpgKeyserverClientImpl(client, parser).getByFingerprint(
                fingerprint = fingerprint.lowercase(),
                config = GpgKeyserverConfig(protocol = protocol),
            ).bind()
            assertEquals(fingerprint, result?.fingerprint)
            assertEquals("FEDCBA9876543210", result?.keyId)
            val request = requests.single()
            when (protocol) {
                GpgKeyserverConfig.Protocol.VKS ->
                    assertEquals(listOf("vks", "v1", "by-fingerprint", fingerprint), request.pathSegments)
                GpgKeyserverConfig.Protocol.HKP ->
                    assertEquals("0x$fingerprint", request.query["search"])
            }
        }
    }

    @Test
    fun `VKS get by email uses by-email endpoint and parses armored response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(
                        fingerprint = TEST_FINGERPRINT,
                        userIds = listOf("Alice Example <alice@example.com>"),
                        emails = listOf("alice@example.com"),
                    ),
                ),
            ),
        )
        val client = recordingClient(
            requests = requests,
            response = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            contentType = ContentType.parse("application/pgp-keys"),
        )

        val results = GpgKeyserverClientImpl(client, parser)
            .getByEmail(
                email = "alice@example.com",
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(TEST_FINGERPRINT, results.single().fingerprint)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("vks", "v1", "by-email", "alice@example.com"),
                    route = GpgKeyserverClientImpl.ROUTE_VKS_BY_EMAIL,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `VKS get by fingerprint uses by-fingerprint endpoint and parses armored response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(
                        fingerprint = TEST_FINGERPRINT,
                        userIds = listOf("Alice Example <alice@example.com>"),
                        emails = listOf("alice@example.com"),
                    ),
                ),
            ),
        )
        val client = recordingClient(
            requests = requests,
            response = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            contentType = ContentType.parse("application/pgp-keys"),
        )

        val result = GpgKeyserverClientImpl(client, parser)
            .getByFingerprint(
                fingerprint = TEST_FINGERPRINT.lowercase(),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(TEST_FINGERPRINT, result?.fingerprint)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("vks", "v1", "by-fingerprint", TEST_FINGERPRINT),
                    route = GpgKeyserverClientImpl.ROUTE_VKS_BY_FINGERPRINT,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `HKP get by fingerprint uses lookup get endpoint and parses armored response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(
                        fingerprint = TEST_FINGERPRINT,
                        userIds = listOf("Alice Example <alice@example.com>"),
                        emails = listOf("alice@example.com"),
                    ),
                ),
            ),
        )
        val client = recordingClient(
            requests = requests,
            response = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            contentType = ContentType.parse("application/pgp-keys"),
        )

        val result = GpgKeyserverClientImpl(client, parser)
            .getByFingerprint(
                fingerprint = TEST_FINGERPRINT.lowercase(),
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(TEST_FINGERPRINT, result?.fingerprint)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("pks", "lookup"),
                    route = GpgKeyserverClientImpl.ROUTE_HKP_GET,
                    query = mapOf(
                        "op" to "get",
                        "options" to "mr",
                        "search" to "0x$TEST_FINGERPRINT",
                    ),
                ),
            ),
            requests,
        )
    }

    @Test
    fun `HKP get by email uses lookup index endpoint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = """
                info:1:1
                pub:0123456789ABCDEF:1:4096:1700000000:1731536000:
                fpr:::::::::ABCDEF0123456789ABCDEF0123456789ABCDEF01:
                uid:Alice%20Example%20%3Calice%40example.com%3E:::::::::
            """.trimIndent(),
            contentType = ContentType.Text.Plain,
        )

        val results = GpgKeyserverClientImpl(client, FakeParser())
            .getByEmail(
                email = "alice@example.com",
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .bind()

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("pks", "lookup"),
                    route = GpgKeyserverClientImpl.ROUTE_HKP_INDEX,
                    query = mapOf(
                        "op" to "index",
                        "options" to "mr",
                        "search" to "alice@example.com",
                    ),
                ),
            ),
            requests,
        )
        assertEquals("ABCDEF0123456789ABCDEF0123456789ABCDEF01", results.single().fingerprint)
    }
}
