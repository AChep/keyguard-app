package com.artemchep.keyguard.common.service.relays.api.fastmail

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GeneratorContext
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FastmailEmailRelayTest {
    @Test
    fun `generate creates masked email through JMAP`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(requests),
        )

        val alias = relay
            .generate(
                context = GeneratorContext(host = "example.com"),
                config = persistentMapOf("apiKey" to "MY_TOKEN"),
            )
            .bind()

        assertEquals("mask@example.net", alias)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://api.fastmail.com/jmap/session",
                    authorization = "Bearer MY_TOKEN",
                ),
                RecordedRequest(
                    method = HttpMethod.Post,
                    url = "https://api.fastmail.com/jmap/api/",
                    authorization = "Bearer MY_TOKEN",
                    contentType = ContentType.Application.Json.toString(),
                ),
            ),
            requests.map { it.copy(body = "") },
        )

        val body = requests[1].json
        assertEquals(
            listOf(
                "https://www.fastmail.com/dev/maskedemail",
                "urn:ietf:params:jmap:core",
            ),
            body["using"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val methodCall = body["methodCalls"]!!.jsonArray.single().jsonArray
        assertEquals("MaskedEmail/set", methodCall[0].jsonPrimitive.content)
        assertEquals("0", methodCall[2].jsonPrimitive.content)

        val args = methodCall[1].jsonObject
        assertEquals("account-1", args["accountId"]!!.jsonPrimitive.content)

        val create = args["create"]!!
            .jsonObject["new-masked-email"]!!
            .jsonObject
        assertEquals("enabled", create["state"]!!.jsonPrimitive.content)
        assertEquals("", create["description"]!!.jsonPrimitive.content)
        assertEquals("https://example.com", create["forDomain"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, create["url"])
        assertEquals(JsonNull, create["emailPrefix"])
    }

    @Test
    fun `generate uses empty forDomain when host is missing`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(requests),
        )

        relay
            .generate(
                context = GeneratorContext(host = " "),
                config = persistentMapOf("apiKey" to "MY_TOKEN"),
            )
            .bind()

        val create = requests[1].json["methodCalls"]!!
            .jsonArray.single()
            .jsonArray[1]
            .jsonObject["create"]!!
            .jsonObject["new-masked-email"]!!
            .jsonObject
        assertEquals("", create["forDomain"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate fails when masked email account id is missing`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(
                requests = requests,
                sessionResponse = """
                    {
                      "apiUrl": "https://api.fastmail.com/jmap/api/",
                      "primaryAccounts": {}
                    }
                """.trimIndent(),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.com"),
                    config = persistentMapOf("apiKey" to "MY_TOKEN"),
                )
                .bind()
        }

        assertTrue(error.message!!.contains("Masked Email account ID is missing"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `generate surfaces notCreated description`() = runTest {
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(
                requests = mutableListOf(),
                setResponse = """
                    {
                      "methodResponses": [
                        [
                          "MaskedEmail/set",
                          {
                            "notCreated": {
                              "new-masked-email": {
                                "type": "rateLimit",
                                "description": "Rate limit exceeded"
                              }
                            }
                          },
                          "0"
                        ]
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.com"),
                    config = persistentMapOf("apiKey" to "MY_TOKEN"),
                )
                .bind()
        }

        assertEquals("Rate limit exceeded", error.message)
    }

    @Test
    fun `generate surfaces JMAP error description`() = runTest {
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(
                requests = mutableListOf(),
                setResponse = """
                    {
                      "methodResponses": [
                        [
                          "error",
                          {
                            "type": "invalidArguments",
                            "description": "Invalid JMAP request"
                          },
                          "0"
                        ]
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.com"),
                    config = persistentMapOf("apiKey" to "MY_TOKEN"),
                )
                .bind()
        }

        assertEquals("Invalid JMAP request", error.message)
    }

    @Test
    fun `generate fails clearly on unauthorized token`() = runTest {
        val relay = FastmailEmailRelay(
            httpClient = recordingClient(
                requests = mutableListOf(),
                sessionStatus = HttpStatusCode.Unauthorized,
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            relay
                .generate(
                    context = GeneratorContext(host = "example.com"),
                    config = persistentMapOf("apiKey" to "MY_TOKEN"),
                )
                .bind()
        }

        assertEquals("Invalid Fastmail API token.", error.message)
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        sessionResponse: String = validSessionResponse,
        setResponse: String = validSetResponse,
        sessionStatus: HttpStatusCode = HttpStatusCode.OK,
        setStatus: HttpStatusCode = HttpStatusCode.OK,
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

            val responseContent = when (request.url.toString()) {
                "https://api.fastmail.com/jmap/session" -> sessionResponse
                "https://api.fastmail.com/jmap/api/" -> setResponse
                else -> error("Unexpected request URL: ${request.url}")
            }
            val responseStatus = when (request.url.toString()) {
                "https://api.fastmail.com/jmap/session" -> sessionStatus
                "https://api.fastmail.com/jmap/api/" -> setStatus
                else -> HttpStatusCode.NotFound
            }
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

    private companion object {
        val apiJson = Json {
            ignoreUnknownKeys = true
        }

        val validSessionResponse = """
            {
              "apiUrl": "https://api.fastmail.com/jmap/api/",
              "primaryAccounts": {
                "https://www.fastmail.com/dev/maskedemail": "account-1"
              }
            }
        """.trimIndent()

        val validSetResponse = """
            {
              "methodResponses": [
                [
                  "MaskedEmail/set",
                  {
                    "created": {
                      "new-masked-email": {
                        "email": "mask@example.net"
                      }
                    }
                  },
                  "0"
                ]
              ]
            }
        """.trimIndent()
    }
}
