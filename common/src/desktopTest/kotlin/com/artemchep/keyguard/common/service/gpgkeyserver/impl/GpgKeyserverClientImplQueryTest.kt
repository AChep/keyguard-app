package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.GpgKeyserverFingerprintMismatchException
import com.artemchep.keyguard.common.exception.GpgKeyserverShortKeyIdException
import com.artemchep.keyguard.common.exception.GpgKeyserverUnsupportedKeyVersionException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.impl.SearchGpgPublicKeyImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GpgKeyserverClientImplQueryTest {
    @Test
    fun `VKS search accepts a fingerprint with separators`() = runTest {
        listOf(
            TEST_FINGERPRINT.chunked(4).joinToString(" "),
            TEST_FINGERPRINT.lowercase().chunked(2).joinToString(":"),
            TEST_FINGERPRINT.chunked(4).joinToString("-"),
        ).forEach { query ->
            val requests = mutableListOf<RecordedRequest>()
            searchRecording(requests, SearchGpgPublicKeyRequest(query))

            assertEquals(
                listOf(
                    RecordedRequest(
                        method = HttpMethod.Get,
                        pathSegments = listOf("vks", "v1", "by-fingerprint", TEST_FINGERPRINT),
                        route = GpgKeyserverClientImpl.ROUTE_VKS_BY_FINGERPRINT,
                    ),
                ),
                requests,
                message = query,
            )
        }
    }

    @Test
    fun `HKP search strips the 0x prefix of a fingerprint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        searchRecording(
            requests = requests,
            request = SearchGpgPublicKeyRequest("0x${TEST_FINGERPRINT.lowercase()}"),
            config = TEST_HKP_CONFIG,
        )

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
    fun `VKS key ID search uses the bare upper-case key ID`() = runTest {
        listOf(
            TEST_KEY_ID,
            "0x${TEST_KEY_ID.lowercase()}",
            " 0X$TEST_KEY_ID ",
        ).forEach { query ->
            val requests = mutableListOf<RecordedRequest>()
            searchRecording(requests, SearchGpgPublicKeyRequest(query))

            assertEquals(
                listOf(
                    RecordedRequest(
                        method = HttpMethod.Get,
                        pathSegments = listOf("vks", "v1", "by-keyid", TEST_KEY_ID),
                        route = GpgKeyserverClientImpl.ROUTE_VKS_BY_KEY_ID,
                    ),
                ),
                requests,
                message = query,
            )
        }
    }

    @Test
    fun `short key ID search fails without a request`() = runTest {
        for (config in listOf(GpgKeyserverConfig(), TEST_HKP_CONFIG)) {
            listOf("0xEFE21092", "efe21092").forEach { query ->
                val requests = mutableListOf<RecordedRequest>()
                val client = recordingClient(
                    requests = requests,
                    response = "info:1:0",
                    contentType = ContentType.Text.Plain,
                )
                // The use case must not fall back to another keyserver either.
                val useCase = SearchGpgPublicKeyImpl(
                    getGpgKeyserverConfig = object : GetGpgKeyserverConfig {
                        override fun invoke() = flowOf(config)
                    },
                    keyserverClient = GpgKeyserverClientImpl(client, FakeParser()),
                )

                assertFailsWith<GpgKeyserverShortKeyIdException>(message = "$config $query") {
                    useCase(SearchGpgPublicKeyRequest(query)).bind()
                }
                assertEquals(emptyList(), requests, message = "$config $query")
            }
        }
    }

    @Test
    fun `HKP key ID search adds the 0x prefix`() = runTest {
        listOf(
            TEST_KEY_ID to "0x$TEST_KEY_ID",
            "0x${TEST_KEY_ID.lowercase()}" to "0x$TEST_KEY_ID",
        ).forEach { (query, search) ->
            val requests = mutableListOf<RecordedRequest>()
            searchRecording(
                requests = requests,
                request = SearchGpgPublicKeyRequest(query),
                config = TEST_HKP_CONFIG,
                response = "info:1:0",
                contentType = ContentType.Text.Plain,
                parser = FakeParser(),
            )

            assertEquals(
                listOf(
                    RecordedRequest(
                        method = HttpMethod.Get,
                        pathSegments = listOf("pks", "lookup"),
                        route = GpgKeyserverClientImpl.ROUTE_HKP_INDEX,
                        query = mapOf(
                            "op" to "index",
                            "options" to "mr",
                            "fingerprint" to "on",
                            "search" to search,
                        ),
                    ),
                ),
                requests,
                message = query,
            )
        }
    }

    @Test
    fun `HKP fingerprint lookup of a key ID issues no request`() = runTest {
        listOf(TEST_KEY_ID, "89ABCDEF").forEach { keyId ->
            val requests = mutableListOf<RecordedRequest>()
            val results = searchRecording(
                requests = requests,
                request = SearchGpgPublicKeyRequest(
                    query = keyId,
                    mode = SearchGpgPublicKeyRequest.Mode.FINGERPRINT,
                ),
                config = TEST_HKP_CONFIG,
            )

            assertEquals(emptyList(), results, message = keyId)
            assertEquals(emptyList(), requests, message = keyId)
        }
    }

    @Test
    fun `an e-mail of hex letters is an e-mail search, not a key ID`() = runTest {
        val email = "ed@cafe.de"
        val hkpRequests = mutableListOf<RecordedRequest>()
        searchRecording(
            requests = hkpRequests,
            request = SearchGpgPublicKeyRequest(email),
            config = TEST_HKP_CONFIG,
            response = "info:1:0",
            contentType = ContentType.Text.Plain,
            parser = FakeParser(),
        )
        assertEquals(email, hkpRequests.single().query["search"])

        val vksRequests = mutableListOf<RecordedRequest>()
        searchRecording(vksRequests, SearchGpgPublicKeyRequest(email))
        assertEquals(listOf("vks", "v1", "by-email", email), vksRequests.single().pathSegments)
    }

    @Test
    fun `fingerprint search keeps only keys with that primary fingerprint`() = runTest {
        val subKeyFingerprint = "C".repeat(24) + "0123456789ABCDEF"
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(fingerprint = "B".repeat(40), userIds = listOf("Mallory"), emails = emptyList()),
                    keyInfo(fingerprint = TEST_FINGERPRINT, userIds = listOf("Alice"), emails = emptyList()),
                    keyInfo(fingerprint = "D".repeat(40), userIds = listOf("Bob"), emails = emptyList())
                        .copy(
                            subKeys = listOf(
                                GpgPublicSubKeyInfo(
                                    fingerprint = subKeyFingerprint,
                                    keyId = "0123456789ABCDEF",
                                    algorithm = "CV25519",
                                    canSign = false,
                                    canEncrypt = true,
                                    revoked = false,
                                    expiresAt = null,
                                ),
                            ),
                        ),
                ),
            ),
        )
        for (config in listOf(GpgKeyserverConfig(), TEST_HKP_CONFIG)) {
            val results = searchRecording(
                requests = mutableListOf(),
                request = SearchGpgPublicKeyRequest(TEST_FINGERPRINT),
                config = config,
                parser = parser,
            )
            assertEquals(listOf(listOf("Alice")), results.map { it.userIds }, message = config.toString())

            // Anyone can bind a copy of a public key to their own key as a
            // subkey, so a subkey match does not identify the key.
            assertFailsWith<GpgKeyserverFingerprintMismatchException>(message = config.toString()) {
                searchRecording(
                    requests = mutableListOf(),
                    request = SearchGpgPublicKeyRequest(subKeyFingerprint),
                    config = config,
                    parser = parser,
                )
            }
            assertFailsWith<GpgKeyserverFingerprintMismatchException>(message = config.toString()) {
                GpgKeyserverClientImpl(
                    recordingClient(
                        requests = mutableListOf(),
                        response = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                        contentType = ContentType.parse("application/pgp-keys"),
                    ),
                    parser,
                ).getByFingerprint(subKeyFingerprint, config).bind()
            }
        }
    }

    @Test
    fun `fingerprint search fails when the keyserver returns another key`() = runTest {
        for (config in listOf(GpgKeyserverConfig(), TEST_HKP_CONFIG)) {
            assertFailsWith<GpgKeyserverFingerprintMismatchException>(message = config.toString()) {
                searchRecording(
                    requests = mutableListOf(),
                    request = SearchGpgPublicKeyRequest("B".repeat(40)),
                    config = config,
                )
            }
        }
    }

    @Test
    fun `VKS v6 fingerprint lookup fails without a request`() = runTest {
        val vks = GpgKeyserverConfig()
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = "malformed fingerprint",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.BadRequest,
        )
        val impl = GpgKeyserverClientImpl(client, FakeParser())

        // VKS keyservers store V4 keys only, and answer a V6 fingerprint
        // with the same 400 as any other malformed one.
        val v6Fingerprint = "A".repeat(64)
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest(v6Fingerprint), vks))
        assertFailsWith<GpgKeyserverUnsupportedKeyVersionException> {
            impl.search(SearchGpgPublicKeyRequest(v6Fingerprint), vks).bind()
        }
        assertFailsWith<GpgKeyserverUnsupportedKeyVersionException> {
            impl.getByFingerprint(v6Fingerprint, vks).bind()
        }
        assertEquals(emptyList(), requests)

        // A 400 is never a missing key.
        assertFailsWith<HttpException> {
            impl.search(SearchGpgPublicKeyRequest(TEST_FINGERPRINT), vks).bind()
        }
    }
}
