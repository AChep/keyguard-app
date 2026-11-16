package com.artemchep.keyguard.common.service.relays.api.migadu

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MigaduEmailRelayTest {
    @Test
    fun `generate creates a public alias using account email and API key authentication`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val alias = relay(httpClient).generate(
                context = GeneratorContext(host = "example.org"),
                config = persistentMapOf(
                    "email" to " Admin@example.net ",
                    "apiKey" to " MY_KEY ",
                    "domain" to " @Example.COM ",
                    "destinationEmail" to " User+alias@example.com ",
                ),
            ).bind()

            assertEquals("az09az09@example.com", alias)
        }

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.migadu.com/v1/domains/example.com/aliases", request.url)
        assertEquals("Basic QWRtaW5AZXhhbXBsZS5uZXQ6TVlfS0VZ", request.authorization)
        assertEquals(ContentType.Application.Json.toString(), request.contentType)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(setOf("local_part", "destinations", "is_internal"), body.keys)
        assertEquals("az09az09", body["local_part"]!!.jsonPrimitive.content)
        assertEquals("false", body["is_internal"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("User+alias@example.com"),
            body["destinations"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
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
    fun `generate rejects invalid domains and emails before sending a request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val relay = relay(httpClient)
            for (domain in listOf("https://example.com", "example.com/path", "example.com?x=1", "example")) {
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
            for (key in listOf("email", "destinationEmail")) {
                for (email in listOf("user", "user@example.com,other@example.com", "user@example.com\nBcc:other")) {
                    val error = assertFailsWith<IllegalArgumentException> {
                        relay.generate(
                            GeneratorContext(host = null),
                            validConfig().putting(key, email),
                        ).bind()
                    }
                    assertEquals(
                        TextHolder.Res(Res.string.error_invalid_email),
                        assertIs<Readable>(error).title,
                    )
                }
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generate surfaces API validation authentication and rate limit errors`() = runTest {
        for (status in listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
        )) {
            client(
                responseStatus = status,
                responseContent = """{"error":"invalid_params","message":"Destination must be on the same domain"}""",
            ).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException> {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                val readable = assertIs<Readable>(error)
                assertEquals(TextHolder.Res(Res.string.error_failed_unknown), readable.title)
                assertEquals(TextHolder.Value("Destination must be on the same domain"), readable.text)
            }
        }
    }

    @Test
    fun `generate falls back to error code or HTTP status when the message is missing`() = runTest {
        for ((content, expected) in listOf(
            """{"error":"invalid_token"}""" to "invalid_token",
            """{"message":" ","error":"invalid_token"}""" to "invalid_token",
            """{"message":null,"error":{}}""" to "Bad Gateway",
            "<html>Bad gateway</html>" to "Bad Gateway",
        )) {
            client(responseStatus = HttpStatusCode.BadGateway, responseContent = content).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException> {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                assertEquals(TextHolder.Value(expected), assertIs<Readable>(error).text)
            }
        }
    }

    @Test
    fun `generate never returns an alias without server confirmation`() = runTest {
        for (content in listOf(
            "{}",
            "[]",
            """{"address":null}""",
            """{"address":123}""",
            """{"address":""}""",
            """{"address":"different@example.com"}""",
            """{"address":"az09az09@example.net"}""",
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
        "email" to "Admin@example.net",
        "apiKey" to "MY_KEY",
        "domain" to "example.com",
        "destinationEmail" to "user@example.com",
    )

    private fun relay(httpClient: HttpClient) = MigaduEmailRelay(
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
        responseContent: String = """
            {
              "address":"az09az09@example.com",
              "domain_name":"example.com",
              "local_part":"az09az09",
              "is_internal":false,
              "destinations":["user@example.com"]
            }
        """.trimIndent(),
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
