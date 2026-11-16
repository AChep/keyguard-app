package com.artemchep.keyguard.common.service.relays.api.mailcow

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.test.TestCryptoGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
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

@Suppress("TooManyFunctions")
class MailcowEmailRelayTest {
    @Test
    fun `generate creates an active alias with the API key header`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val alias = relay(httpClient).generate(
                context = GeneratorContext(host = "example.org"),
                config = persistentMapOf(
                    "base_url" to " https://mail.example.com/ ",
                    "apiKey" to " MY_KEY ",
                    "domain" to " @Example.COM ",
                    "destinationEmail" to " User+alias@example.net ",
                ),
            ).bind()
            assertEquals("az09az09@example.com", alias)
        }

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://mail.example.com/api/v1/add/alias", request.url)
        assertEquals("MY_KEY", request.apiKey)
        assertEquals(ContentType.Application.Json.toString(), request.contentType)
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(setOf("address", "goto", "active"), body.keys)
        assertEquals("az09az09@example.com", body["address"]!!.jsonPrimitive.content)
        assertEquals("User+alias@example.net", body["goto"]!!.jsonPrimitive.content)
        assertEquals("true", body["active"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate supports custom ports paths and local servers without closing the shared client`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            val relay = relay(httpClient)
            for (baseUrl in listOf(
                "https://mail.example.com:8443",
                "https://mail.example.com/mailcow/",
                "http://192.168.1.5:8080",
                "http://[::1]:8080/",
            )) {
                val alias = relay.generate(
                    GeneratorContext(host = null),
                    validConfig().putting("base_url", baseUrl),
                ).bind()
                assertEquals("az09az09@example.com", alias)
                assertEquals(baseUrl.trimEnd('/') + "/api/v1/add/alias", requests.last().url)
            }
        }
        assertEquals(4, requests.size)
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
    fun `generate rejects ambiguous or invalid server URLs before sending credentials`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            for (baseUrl in listOf(
                "mail.example.com",
                "ftp://mail.example.com",
                "https://",
                "https:///path",
                "https://:8443",
                "https://user:password@mail.example.com",
                "https://mail.example.com?query=1",
                "https://mail.example.com?",
                "https://mail.example.com#fragment",
            )) {
                val error = assertFailsWith<IllegalArgumentException>(baseUrl) {
                    relay(httpClient).generate(
                        GeneratorContext(host = null),
                        validConfig().putting("base_url", baseUrl),
                    ).bind()
                }
                assertEquals(TextHolder.Res(Res.string.error_invalid_url), assertIs<Readable>(error).title)
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generate rejects invalid alias domains and destinations before sending a request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(requests).use { httpClient ->
            for ((key, value, resource) in listOf(
                Triple("domain", "example.com/path", Res.string.error_invalid_domain),
                Triple("domain", "example.com?query=1", Res.string.error_invalid_domain),
                Triple("destinationEmail", "user", Res.string.error_invalid_email),
                Triple("destinationEmail", "a@example.net,b@example.net", Res.string.error_invalid_email),
            )) {
                val error = assertFailsWith<IllegalArgumentException> {
                    relay(httpClient).generate(
                        GeneratorContext(host = null),
                        validConfig().putting(key, value),
                    ).bind()
                }
                assertEquals(TextHolder.Res(resource), assertIs<Readable>(error).title)
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generate accepts an object confirmation with an alias ID`() = runTest {
        client(responseContent = """{"type":"success","msg":["alias_added","az09az09@example.com",123]}""")
            .use { httpClient ->
                val alias = relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                assertEquals("az09az09@example.com", alias)
            }
    }

    @Test
    fun `generate surfaces HTTP and application errors including mixed results`() = runTest {
        for ((status, content, expected) in listOf(
            Triple(HttpStatusCode.OK, """[{"type":"danger","msg":["alias_exists","a@example.com"]}]""",
                "alias_exists: a@example.com"),
            Triple(HttpStatusCode.OK, """{"type":"error","msg":"Permission denied"}""", "Permission denied"),
            Triple(HttpStatusCode.OK,
                """[{"type":"success","msg":["alias_added","az09az09@example.com"]},
                    {"type":"danger","msg":"Rejected"}]""", "Rejected"),
            Triple(HttpStatusCode.Unauthorized, """{"type":"error","msg":"authentication failed"}""",
                "authentication failed"),
            Triple(HttpStatusCode.Forbidden, """{"type":"error","msg":"API read/write access denied"}""",
                "API read/write access denied"),
            Triple(HttpStatusCode.TooManyRequests, "{}", "Too Many Requests"),
            Triple(HttpStatusCode.BadGateway, "<html>Bad gateway</html>", "Bad Gateway"),
        )) {
            client(responseStatus = status, responseContent = content).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException>(content) {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                val readable = assertIs<Readable>(error)
                assertEquals(TextHolder.Res(Res.string.error_failed_unknown), readable.title)
                assertEquals(TextHolder.Value(expected), readable.text)
            }
        }
    }

    @Test
    fun `generate never returns an alias without explicit server confirmation`() = runTest {
        for (content in listOf(
            "{}",
            "[]",
            "null",
            """{"type":"success","msg":"Task completed"}""",
            """{"type":"success","msg":["alias_added","other@example.com"]}""",
            """{"type":"success","msg":["mailbox_added","az09az09@example.com"]}""",
            """[{"type":"success","msg":["alias_added","az09az09@example.com"]},null]""",
            "<html>Not JSON</html>",
        )) {
            client(responseContent = content).use { httpClient ->
                val error = assertFailsWith<IllegalArgumentException>(content) {
                    relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
                }
                assertEquals(TextHolder.Res(Res.string.error_failed_unknown), assertIs<Readable>(error).title)
            }
        }
    }

    @Test
    fun `generate does not forward the API key to redirect destinations`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        client(
            requests = requests,
            responseStatus = HttpStatusCode.TemporaryRedirect,
            location = "https://other.example.com/api/v1/add/alias",
        ).use { httpClient ->
            val error = assertFailsWith<IllegalArgumentException> {
                relay(httpClient).generate(GeneratorContext(host = null), validConfig()).bind()
            }
            assertEquals(TextHolder.Value("Temporary Redirect"), assertIs<Readable>(error).text)
        }
        assertEquals(1, requests.size)
        assertEquals("https://mail.example.com/api/v1/add/alias", requests.single().url)
    }

    private fun validConfig() = persistentMapOf(
        "base_url" to "https://mail.example.com",
        "apiKey" to "MY_KEY",
        "domain" to "example.com",
        "destinationEmail" to "user@example.net",
    )

    private fun relay(httpClient: HttpClient) = MailcowEmailRelay(
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
        responseContent: String = """[{"type":"success","msg":["alias_added","az09az09@example.com"]}]""",
        location: String? = null,
    ) = HttpClient(MockEngine { request ->
        requests += RecordedRequest(
            method = request.method,
            url = request.url.toString(),
            apiKey = request.headers["X-API-Key"],
            contentType = request.body.contentType?.toString(),
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
        )
        respond(
            content = responseContent,
            status = responseStatus,
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                if (location != null) append(HttpHeaders.Location, location)
            },
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
        val apiKey: String?,
        val contentType: String?,
        val body: String,
    )
}
