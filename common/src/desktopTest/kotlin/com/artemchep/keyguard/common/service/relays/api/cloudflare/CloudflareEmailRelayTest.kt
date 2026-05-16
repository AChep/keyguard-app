package com.artemchep.keyguard.common.service.relays.api.cloudflare

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudflareEmailRelayTest {
    @Test
    fun `generate creates email routing rule`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = CloudflareEmailRelay(
            httpClient = recordingClient(requests),
            cryptoGenerator = FakeCryptoGenerator(
                values = (0 until 16).toMutableList(),
            ),
        )

        val alias = relay
            .generate(
                context = GeneratorContext(host = "example.org"),
                config = persistentMapOf(
                    "apiToken" to "MY_TOKEN",
                    "zoneId" to "ZONE_ID",
                    "domain" to "example.com",
                    "destinationEmail" to "user@example.net",
                ),
            )
            .bind()

        assertEquals("abcdefghijklmnop@example.com", alias)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Post,
                    url = "https://api.cloudflare.com/client/v4/zones/ZONE_ID/email/routing/rules",
                    authorization = "Bearer MY_TOKEN",
                    contentType = ContentType.Application.Json.toString(),
                ),
            ),
            requests.map { it.copy(body = "") },
        )

        val body = requests.single().json
        assertEquals("Keyguard: example.org", body["name"]!!.jsonPrimitive.content)
        assertEquals("true", body["enabled"]!!.jsonPrimitive.content)

        val action = body["actions"]!!.jsonArray.single().jsonObject
        assertEquals("forward", action["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("user@example.net"),
            action["value"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val matcher = body["matchers"]!!.jsonArray.single().jsonObject
        assertEquals("literal", matcher["type"]!!.jsonPrimitive.content)
        assertEquals("to", matcher["field"]!!.jsonPrimitive.content)
        assertEquals("abcdefghijklmnop@example.com", matcher["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate normalizes domain`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = CloudflareEmailRelay(
            httpClient = recordingClient(requests),
            cryptoGenerator = FakeCryptoGenerator(
                values = MutableList(16) { 0 },
            ),
        )

        val alias = relay
            .generate(
                context = GeneratorContext(host = null),
                config = persistentMapOf(
                    "apiToken" to "MY_TOKEN",
                    "zoneId" to "ZONE_ID",
                    "domain" to " @Example.COM ",
                    "destinationEmail" to "user@example.net",
                ),
            )
            .bind()

        assertEquals("aaaaaaaaaaaaaaaa@example.com", alias)
        val matcher = requests.single().json["matchers"]!!
            .jsonArray.single()
            .jsonObject
        assertEquals("aaaaaaaaaaaaaaaa@example.com", matcher["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate fails before http when required config is missing`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = CloudflareEmailRelay(
            httpClient = recordingClient(requests),
            cryptoGenerator = FakeCryptoGenerator(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.org"),
                    config = persistentMapOf(
                        "apiToken" to "MY_TOKEN",
                        "zoneId" to "ZONE_ID",
                        "domain" to "example.com",
                    ),
                )
                .bind()
        }

        assertEquals("Destination email is required for creating an email alias.", error.message)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `generate surfaces http error message`() = runTest {
        val relay = CloudflareEmailRelay(
            httpClient = recordingClient(
                requests = mutableListOf(),
                responseStatus = HttpStatusCode.Forbidden,
                responseContent = """
                    {
                      "success": false,
                      "errors": [
                        {
                          "code": 10000,
                          "message": "Token lacks Email Routing Rules permission"
                        }
                      ],
                      "messages": [],
                      "result": null
                    }
                """.trimIndent(),
            ),
            cryptoGenerator = FakeCryptoGenerator(
                values = MutableList(16) { 0 },
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.org"),
                    config = validConfig(),
                )
                .bind()
        }

        assertEquals(
            "Token lacks Email Routing Rules permission (10000)",
            error.message,
        )
    }

    @Test
    fun `generate surfaces success false message`() = runTest {
        val relay = CloudflareEmailRelay(
            httpClient = recordingClient(
                requests = mutableListOf(),
                responseContent = """
                    {
                      "success": false,
                      "errors": [
                        {
                          "message": "Destination address has not been verified"
                        }
                      ],
                      "messages": [],
                      "result": null
                    }
                """.trimIndent(),
            ),
            cryptoGenerator = FakeCryptoGenerator(
                values = MutableList(16) { 0 },
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.org"),
                    config = validConfig(),
                )
                .bind()
        }

        assertEquals("Destination address has not been verified", error.message)
    }

    private fun validConfig() = persistentMapOf(
        "apiToken" to "MY_TOKEN",
        "zoneId" to "ZONE_ID",
        "domain" to "example.com",
        "destinationEmail" to "user@example.net",
    )

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        responseContent: String = validRuleResponse,
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(
        MockEngine { request ->
            requests += RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                contentType = request.headers[HttpHeaders.ContentType]
                    ?: request.body.contentType?.toString(),
                body = request.body.asText(),
            )

            respond(
                content = responseContent,
                status = responseStatus,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(apiJson))
        }
    }

    private fun OutgoingContent.asText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unsupported request body type: ${this::class}")
    }

    private val RecordedRequest.json: JsonObject
        get() = apiJson.parseToJsonElement(body).jsonObject

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val authorization: String? = null,
        val contentType: String? = null,
        val body: String = "",
    )

    private class FakeCryptoGenerator(
        private val values: MutableList<Int> = mutableListOf(),
    ) : CryptoGenerator {
        override fun hkdf(
            seed: ByteArray,
            salt: ByteArray?,
            info: ByteArray?,
            length: Int,
        ): ByteArray = unsupported()

        override fun pbkdf2(
            seed: ByteArray,
            salt: ByteArray,
            iterations: Int,
            length: Int,
        ): ByteArray = unsupported()

        override fun argon2(
            mode: Argon2Mode,
            seed: ByteArray,
            salt: ByteArray,
            iterations: Int,
            memoryKb: Int,
            parallelism: Int,
        ): ByteArray = unsupported()

        override fun seed(length: Int): ByteArray = unsupported()

        override fun hmac(
            key: ByteArray,
            data: ByteArray,
            algorithm: CryptoHashAlgorithm,
        ): ByteArray = unsupported()

        override fun hashSha1(data: ByteArray): ByteArray = unsupported()

        override fun hashSha256(data: ByteArray): ByteArray = unsupported()

        override fun hashMd5(data: ByteArray): ByteArray = unsupported()

        override fun uuid(): String = unsupported()

        override fun random(): Int = unsupported()

        override fun random(range: IntRange): Int {
            val value = values.removeAt(0)
            val size = range.last - range.first + 1
            return range.first + value.rem(size)
        }

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException()
    }

    private companion object {
        val apiJson = Json {
            ignoreUnknownKeys = true
        }

        val validRuleResponse = """
            {
              "success": true,
              "errors": [],
              "messages": [],
              "result": {
                "id": "rule-id"
              }
            }
        """.trimIndent()
    }
}
