package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpgKeyserverClientImplSearchTest {
    @Test
    fun `VKS email search uses by-email endpoint and parses armored response`() = runTest {
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
            .search(
                request = SearchGpgPublicKeyRequest("alice+test@example.com"),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(1, results.size)
        assertEquals(TEST_FINGERPRINT, results.single().fingerprint)
        assertEquals("https://keys.openpgp.org", results.single().sourceKeyserver)
        assertEquals(GpgKeyserverConfig(), results.single().sourceKeyserverConfig)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("vks", "v1", "by-email", "alice+test@example.com"),
                    route = GpgKeyserverClientImpl.ROUTE_VKS_BY_EMAIL,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `search uses request keyserver config protocol for fingerprint lookup`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val hkpConfig = GpgKeyserverConfig(
            url = GpgKeyserverConfig.HKP_UBUNTU_URL,
            protocol = GpgKeyserverConfig.Protocol.HKP,
        )
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
            .search(
                request = SearchGpgPublicKeyRequest(
                    query = TEST_FINGERPRINT.lowercase(),
                    mode = SearchGpgPublicKeyRequest.Mode.FINGERPRINT,
                    keyserverConfig = hkpConfig,
                ),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        val result = results.single()
        assertEquals(TEST_FINGERPRINT, result.fingerprint)
        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", result.publicKeyArmored)
        assertEquals(GpgKeyserverConfig.HKP_UBUNTU_URL, result.sourceKeyserver)
        assertEquals(hkpConfig, result.sourceKeyserverConfig)
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
    fun `HKP search parses machine readable index rows`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val hkpConfig = GpgKeyserverConfig(
            url = GpgKeyserverConfig.HKP_UBUNTU_URL,
            protocol = GpgKeyserverConfig.Protocol.HKP,
        )
        val client = recordingClient(
            requests = requests,
            response = """
                info:1:1
                pub:0123456789ABCDEF:1:4096:1700000000:1731536000:
                fpr:::::::::ABCDEF0123456789ABCDEF0123456789ABCDEF01:
                uid:Alice%20Example%20%3Calice%40example.com%3E:::::::::
                uid:Name%20%3Cfirst%40example.com%3E%20%3Csecond%40example.com%3E:::::::::
                uid:%3Cbad%3Cgood%40example.com%3E:::::::::
            """.trimIndent(),
            contentType = ContentType.Text.Plain,
        )

        val results = GpgKeyserverClientImpl(client, FakeParser())
            .search(
                request = SearchGpgPublicKeyRequest("Alice Example"),
                config = hkpConfig,
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
                        "search" to "Alice Example",
                    ),
                ),
            ),
            requests,
        )
        val result = results.single()
        assertEquals("ABCDEF0123456789ABCDEF0123456789ABCDEF01", result.fingerprint)
        assertEquals("0123456789ABCDEF", result.keyId)
        assertEquals(
            listOf(
                "Alice Example <alice@example.com>",
                "Name <first@example.com> <second@example.com>",
                "<bad<good@example.com>",
            ),
            result.userIds,
        )
        assertEquals(listOf("alice@example.com"), result.emails)
        assertEquals("RSA", result.algorithm)
        assertEquals("https://keyserver.ubuntu.com", result.sourceKeyserver)
        assertEquals(hkpConfig, result.sourceKeyserverConfig)
        assertTrue(result.publicKeyArmored == null)
    }

    @Test
    fun `HKP index fingerprints derive version-appropriate long key IDs`() = runTest {
        for (fingerprint in listOf("B".repeat(24) + "0123456789ABCDEF", "FEDCBA9876543210" + "A".repeat(48))) {
            val client = recordingClient(
                requests = mutableListOf(),
                response = "info:1:1\npub:$fingerprint:27:255:1700000000::\nuid:Alice:::::::::\n",
                contentType = ContentType.Text.Plain,
            )
            val result = GpgKeyserverClientImpl(client, FakeParser()).search(
                request = SearchGpgPublicKeyRequest("Alice"),
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            ).bind().single()
            assertEquals(fingerprint, result.fingerprint)
            assertEquals(if (fingerprint.length == 40) "0123456789ABCDEF" else "FEDCBA9876543210", result.keyId)
        }
    }

    @Test
    fun `VKS text search cannot be served and issues no request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = "info:1:0",
            contentType = ContentType.Text.Plain,
        )

        val impl = GpgKeyserverClientImpl(client, FakeParser())
        // The transport no longer silently re-routes a free-text query to the
        // Ubuntu HKP server; it honestly reports it cannot serve the query and
        // makes no network request. SearchGpgPublicKeyImpl owns the fallback.
        assertEquals(
            false,
            impl.canServeSearch(
                request = SearchGpgPublicKeyRequest("Alice Example"),
                config = GpgKeyserverConfig(),
            ),
        )

        val results = impl
            .search(
                request = SearchGpgPublicKeyRequest("Alice Example"),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertTrue(results.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `canServeSearch reflects protocol and resolved mode`() {
        val impl = GpgKeyserverClientImpl(
            recordingClient(
                requests = mutableListOf(),
                response = "",
                contentType = ContentType.Text.Plain,
            ),
            FakeParser(),
        )
        val vks = GpgKeyserverConfig()
        val hkp = GpgKeyserverConfig(
            url = GpgKeyserverConfig.HKP_UBUNTU_URL,
            protocol = GpgKeyserverConfig.Protocol.HKP,
        )

        // VKS can serve fingerprint / key-id / e-mail lookups, but not free text.
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest(TEST_FINGERPRINT), vks))
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest("alice@example.com"), vks))
        assertEquals(false, impl.canServeSearch(SearchGpgPublicKeyRequest("Alice Example"), vks))
        // An explicit TEXT mode is never servable by VKS either.
        assertEquals(
            false,
            impl.canServeSearch(
                SearchGpgPublicKeyRequest("alice@example.com", SearchGpgPublicKeyRequest.Mode.TEXT),
                vks,
            ),
        )
        // HKP has a free-text index endpoint, so it can serve any query.
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest("Alice Example"), hkp))
        assertTrue(
            impl.canServeSearch(
                SearchGpgPublicKeyRequest(
                    query = "Alice Example",
                    keyserverConfig = hkp,
                ),
                vks,
            ),
        )
        // An empty query is trivially servable (search() short-circuits to empty).
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest("   "), vks))
    }
}
