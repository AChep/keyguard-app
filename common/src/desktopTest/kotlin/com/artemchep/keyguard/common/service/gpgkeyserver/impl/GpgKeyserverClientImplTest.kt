package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headers
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GpgKeyserverClientImplTest {
    @Test
    fun `VKS email search uses by-email endpoint and parses armored response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(
                        fingerprint = fingerprint,
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
        assertEquals(fingerprint, results.single().fingerprint)
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
    fun `VKS get by email uses by-email endpoint and parses armored response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val parser = FakeParser(
            result = GpgPublicKeyParseResult.Success(
                keys = listOf(
                    keyInfo(
                        fingerprint = fingerprint,
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
        assertEquals(fingerprint, results.single().fingerprint)
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
                        fingerprint = fingerprint,
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
                fingerprint = fingerprint.lowercase(),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(fingerprint, result?.fingerprint)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("vks", "v1", "by-fingerprint", fingerprint),
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
                        fingerprint = fingerprint,
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
                fingerprint = fingerprint.lowercase(),
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        assertEquals(fingerprint, result?.fingerprint)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    pathSegments = listOf("pks", "lookup"),
                    route = GpgKeyserverClientImpl.ROUTE_HKP_GET,
                    query = mapOf(
                        "op" to "get",
                        "options" to "mr",
                        "search" to "0x$fingerprint",
                    ),
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
                        fingerprint = fingerprint,
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
                    query = fingerprint.lowercase(),
                    mode = SearchGpgPublicKeyRequest.Mode.FINGERPRINT,
                    keyserverConfig = hkpConfig,
                ),
                config = GpgKeyserverConfig(),
            )
            .bind()

        assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----", parser.armoredInputs.single())
        val result = results.single()
        assertEquals(fingerprint, result.fingerprint)
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
                        "search" to "0x$fingerprint",
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
        assertTrue(impl.canServeSearch(SearchGpgPublicKeyRequest(fingerprint), vks))
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

    @Test
    fun `VKS upload posts keytext as JSON to upload endpoint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc+def\n-----END PGP PUBLIC KEY BLOCK-----"
        val client = recordingClient(
            requests = requests,
            response = """{"key_fpr":"$fingerprint","status":{"alice@example.com":"unpublished"},"token":"abc"}""",
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = armored,
                config = GpgKeyserverConfig(),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("vks", "v1", "upload"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_UPLOAD, request.route)
        assertEquals(ContentType.Application.Json, request.contentType?.withoutParameters())
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(armored, body.getValue("keytext").jsonPrimitive.content)

        assertEquals(fingerprint, result.fingerprint)
        assertEquals("abc", result.token)
        assertEquals(
            mapOf("alice@example.com" to DGpgKeyserverUploadResult.EmailStatus.UNPUBLISHED),
            result.emailStatus,
        )
        assertEquals(setOf("alice@example.com"), result.verifiableEmails)
    }

    @Test
    fun `VKS request-verify posts token and addresses as JSON`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = """
                {
                  "key_fpr": "$fingerprint",
                  "status": {"alice@example.com": "pending", "bob@example.com": "published"},
                  "token": "abc"
                }
            """.trimIndent(),
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .requestVerify(
                token = "abc",
                addresses = listOf("alice@example.com", " alice@example.com ", ""),
                config = GpgKeyserverConfig(),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("vks", "v1", "request-verify"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_REQUEST_VERIFY, request.route)
        assertEquals(ContentType.Application.Json, request.contentType?.withoutParameters())
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("abc", body.getValue("token").jsonPrimitive.content)
        assertEquals(
            listOf("alice@example.com"),
            body.getValue("addresses").jsonArray.map { it.jsonPrimitive.content },
        )

        assertEquals(
            mapOf(
                "alice@example.com" to DGpgKeyserverUploadResult.EmailStatus.PENDING,
                "bob@example.com" to DGpgKeyserverUploadResult.EmailStatus.PUBLISHED,
            ),
            result.emailStatus,
        )
    }

    @Test
    fun `HKP request-verify is unsupported and issues no request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = "",
            contentType = ContentType.Text.Plain,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .requestVerify(
                token = "abc",
                addresses = listOf("alice@example.com"),
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .attempt()
            .bind()

        assertIs<UnsupportedOperationException>(result.leftOrNull())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `VKS upload fails when the response has no fingerprint`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = """{"status":{}}""",
            contentType = ContentType.Application.Json,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                config = GpgKeyserverConfig(),
            )
            .attempt()
            .bind()

        assertIs<IllegalStateException>(result.leftOrNull())
    }

    @Test
    fun `VKS upload surfaces the keyserver error message`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = """{"error":"expected application/json data."}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                config = GpgKeyserverConfig(),
            )
            .attempt()
            .bind()

        val error = assertIs<HttpException>(result.leftOrNull())
        assertEquals(HttpStatusCode.BadRequest, error.statusCode)
        assertEquals(GpgKeyserverClientImpl.ROUTE_VKS_UPLOAD, error.route)
        assertEquals("""{"error":"expected application/json data."}""", error.message)
    }

    @Test
    fun `HKP upload posts keytext to add endpoint`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc\n-----END PGP PUBLIC KEY BLOCK-----"
        val client = recordingClient(
            requests = requests,
            response = "",
            contentType = ContentType.Text.Plain,
        )

        val result = GpgKeyserverClientImpl(client, FakeParser())
            .upload(
                publicKeyArmored = armored,
                config = GpgKeyserverConfig(
                    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                    protocol = GpgKeyserverConfig.Protocol.HKP,
                ),
            )
            .bind()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(listOf("pks", "add"), request.pathSegments)
        assertEquals(GpgKeyserverClientImpl.ROUTE_HKP_ADD, request.route)
        assertEquals(armored, parseQueryString(request.body)["keytext"])
        assertEquals(DGpgKeyserverUploadResult(), result)
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        response: String,
        contentType: ContentType,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            requests += RecordedRequest(
                method = request.method,
                pathSegments = request.url.segments,
                route = request.attributes.getOrNull(routeAttribute),
                query = request.url.parameters.entries().associate { (key, values) ->
                    key to values.single()
                },
                body = request.body.asText(),
                contentType = request.body.contentType,
            )
            respond(
                content = response,
                status = status,
                headers = headers {
                    append(HttpHeaders.ContentType, contentType.toString())
                },
            )
        },
    )

    private data class RecordedRequest(
        val method: HttpMethod,
        val pathSegments: List<String>,
        val route: String?,
        val query: Map<String, String> = emptyMap(),
        val body: String = "",
        val contentType: ContentType? = null,
    )

    private fun OutgoingContent.asText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        is TextContent -> text
        else -> error("Unsupported outgoing content: ${this::class}")
    }

    private class FakeParser(
        private val result: GpgPublicKeyParseResult = GpgPublicKeyParseResult.Success(emptyList()),
    ) : GpgPublicKeyParser {
        val armoredInputs = mutableListOf<String>()

        override fun parse(
            armored: String,
        ): GpgPublicKeyParseResult {
            armoredInputs += armored
            return result
        }
    }

    private fun keyInfo(
        fingerprint: String,
        userIds: List<String>,
        emails: List<String>,
    ) = GpgPublicKeyInfo(
        fingerprint = fingerprint,
        keyId = fingerprint.takeLast(16),
        algorithm = "ED25519",
        bitStrength = null,
        userIds = userIds,
        emails = emails,
        createdAt = null,
        expiresAt = null,
        revoked = false,
        canSign = true,
        canEncrypt = false,
        publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
        subKeys = emptyList(),
    )

    companion object {
        private const val fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
    }
}
