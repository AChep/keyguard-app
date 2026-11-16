package com.artemchep.keyguard.common.service.relays.api.improvmx

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.test.TestCryptoGenerator
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImprovMxEmailRelayTest {
    @Test
    fun `generate creates an alias with basic authentication and normalizes config`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val alias = relay(httpClient).generate(
                context = GeneratorContext(host = "example.org"),
                config = persistentMapOf(
                    "apiKey" to " MY_KEY ",
                    "domain" to " @Example.COM ",
                    "destinationEmail" to " User+alias@example.net ",
                ),
            ).bind()

            assertEquals("az09az09@example.com", alias)
        }

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.improvmx.com/v4/domains/example.com/aliases", request.url)
        assertEquals("Basic YXBpOk1ZX0tFWQ==", request.authorization)
        assertEquals(ContentType.Application.Json.toString(), request.contentType)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(setOf("alias", "forward"), body.keys)
        assertEquals("az09az09", body["alias"]!!.jsonPrimitive.content)
        assertEquals("User+alias@example.net", body["forward"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate rejects missing and blank fields before sending a request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val relay = relay(httpClient)
            for (key in validConfig().keys) {
                for (value in listOf(null, "", " \n ")) {
                    val config = if (value == null) {
                        validConfig().removing(key)
                    } else {
                        validConfig().putting(key, value)
                    }
                    val error = assertFailsWith<IllegalArgumentException>("$key: $value") {
                        relay.generate(GeneratorContext(host = null), config).bind()
                    }
                    val readable = assertIs<Readable>(error)
                    assertEquals(relay.schema.getValue(key).title, readable.title)
                    assertEquals(TextHolder.Res(Res.string.error_must_not_be_blank), readable.text)
                }
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generate rejects invalid domains and destinations before sending a request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val relay = relay(httpClient)
            for (domain in listOf(
                "https://example.com",
                "example.com/aliases",
                "example.com?x=1",
                "example",
            )) {
                val error = assertFailsWith<IllegalArgumentException> {
                    relay.generate(
                        GeneratorContext(host = null),
                        validConfig().putting("domain", domain),
                    ).bind()
                }
                assertEquals(
                    TextHolder.Res(Res.string.error_invalid_domain),
                    assertIs<Readable>(error).title,
                )
            }
            for (email in listOf("user", "user@example.net,other@example.net", "user@example.net\nBcc:other")) {
                val error = assertFailsWith<IllegalArgumentException> {
                    relay.generate(
                        GeneratorContext(host = null),
                        validConfig().putting("destinationEmail", email),
                    ).bind()
                }
                assertEquals(TextHolder.Res(Res.string.error_invalid_email), assertIs<Readable>(error).title)
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generate surfaces API errors including authentication and rate limits`() = runTest {
        for (status in listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
        )) {
            client(
                responseStatus = status,
                responseContent = """{"message":"Alias limit reached","errors":{}}""",
            ).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException> {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                val readable = assertIs<Readable>(error)
                assertEquals(TextHolder.Res(Res.string.error_failed_unknown), readable.title)
                assertEquals(TextHolder.Value("Alias limit reached"), readable.text)
            }
        }
    }

    @Test
    fun `generate falls back to status for non-JSON error responses`() = runTest {
        client(
            responseStatus = HttpStatusCode.BadGateway,
            responseContent = "<html>Bad gateway</html>",
        ).use { httpClient ->
            val error = assertFailsWith<IllegalArgumentException> {
                relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
            }
            assertEquals(TextHolder.Value("Bad Gateway"), assertIs<Readable>(error).text)
        }
    }

    @Test
    fun `generate rejects a reported failure even with an alias in the response`() = runTest {
        client(
            responseContent = """{"success":false,"message":"Domain not found","alias":{"alias":"az09az09"}}""",
        ).use { httpClient ->
            val error = assertFailsWith<IllegalArgumentException> {
                relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
            }
            assertEquals(TextHolder.Value("Domain not found"), assertIs<Readable>(error).text)
        }
    }

    @Test
    fun `generate never returns an alias without server confirmation`() = runTest {
        for (content in listOf(
            "{}",
            """{"alias":null}""",
            """{"alias":"az09az09"}""",
            """{"alias":{}}""",
            """{"alias":{"alias":null}}""",
            """{"alias":{"alias":""}}""",
            """{"alias":{"alias":"different"}}""",
            "<html>Not JSON</html>",
        )) {
            client(responseContent = content).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException>(content) {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                assertEquals(
                    TextHolder.Res(Res.string.error_failed_unknown),
                    assertIs<Readable>(error).title,
                )
            }
        }
    }

    private fun validConfig() = persistentMapOf(
        "apiKey" to "MY_KEY",
        "domain" to "example.com",
        "destinationEmail" to "user@example.net",
    )

    private fun relay(httpClient: HttpClient) = ImprovMxEmailRelay(
        base64Service = Base64ServiceImpl(),
        httpClient = httpClient,
        cryptoGenerator = object : TestCryptoGenerator() {
            private var index = 0

            override fun random(range: IntRange): Int = listOf(0, 25, 26, 35)[index++ % 4]
                .also { assertTrue(it in range) }
        },
    )

    private fun client(
        requests: MutableList<RecordedRequest> = mutableListOf(),
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        responseContent: String = """{"alias":{"alias":"az09az09","forward":"user@example.net","id":"11"}}""",
    ) = HttpClient(MockEngine { request ->
        requests += RecordedRequest(
            method = request.method,
            url = request.url.toString(),
            authorization = request.headers[HttpHeaders.Authorization],
            contentType = request.body.contentType?.toString(),
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
        )
        respond(
            content = responseContent,
            status = responseStatus,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }) {
        install(ContentNegotiation) {
            register(
                ContentType.Application.Json,
                KotlinxSerializationConverter(Json { ignoreUnknownKeys = true }),
            )
        }
    }

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )
}
