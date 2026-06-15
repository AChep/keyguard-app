package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnectionConfig
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.TransferFormat
import com.artemchep.keyguard.util.signalr.internal.util.negotiate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubNegotiateTest {
    @Test
    fun `negotiate request urls match reference resolver cases`() = runTest {
        val cases = listOf(
            NegotiationUrlCase(
                baseUrl = "https://example.com/hub/",
                negotiateUrl = "https://example.com/hub/negotiate?negotiateVersion=1",
            ),
            NegotiationUrlCase(
                baseUrl = "https://example.com/hub",
                negotiateUrl = "https://example.com/hub/negotiate?negotiateVersion=1",
            ),
            NegotiationUrlCase(
                baseUrl = "https://example.com/endpoint?q=my/Data",
                negotiateUrl = "https://example.com/endpoint/negotiate?q=my/Data&negotiateVersion=1",
            ),
            NegotiationUrlCase(
                baseUrl = "https://example.com/endpoint/?q=my/Data",
                negotiateUrl = "https://example.com/endpoint/negotiate?q=my/Data&negotiateVersion=1",
            ),
            NegotiationUrlCase(
                baseUrl = "https://example.com/endpoint/path/more?q=my/Data",
                negotiateUrl = "https://example.com/endpoint/path/more/negotiate?q=my/Data&negotiateVersion=1",
            ),
            NegotiationUrlCase(
                baseUrl = "https://example.com/hub/?negotiateVersion=2",
                negotiateUrl = "https://example.com/hub/negotiate?negotiateVersion=2",
            ),
        )

        cases.forEach { case ->
            val client = HttpClient(
                MockEngine { request ->
                    assertEquals(case.negotiateUrl, request.url.toString())
                    respond(
                        content = successfulNegotiationResponse(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            )
            val options = testOptions(
                client = client,
                baseUrl = case.baseUrl,
                transferFormat = TransferFormat.Text,
            )

            try {
                negotiate(options)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `negotiate v1 uses connection token in url but exposes connection id`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "negotiateVersion": 1,
                          "connectionId": "public-id",
                          "connectionToken": "secret-token",
                          "availableTransports": [
                            {
                              "transport": "WebSockets",
                              "transferFormats": ["Binary"]
                            }
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Binary,
        )

        try {
            val negotiation = negotiate(options)

            assertEquals("https://example.com/hub?id=secret-token", negotiation.url)
            assertEquals("public-id", negotiation.connectionId)
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate v0 ignores connection token in favor of connection id`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "connectionId": "public-id",
                          "connectionToken": "secret-token",
                          "availableTransports": [
                            {
                              "transport": "WebSockets",
                              "transferFormats": ["Binary"]
                            }
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Binary,
        )

        try {
            val negotiation = negotiate(options)

            assertEquals("https://example.com/hub?id=public-id", negotiation.url)
            assertEquals("public-id", negotiation.connectionId)
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate rejects transports without requested transfer format`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "negotiateVersion": 1,
                          "connectionId": "public-id",
                          "connectionToken": "secret-token",
                          "availableTransports": [
                            {
                              "transport": "WebSockets",
                              "transferFormats": ["Binary"]
                            }
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Text,
        )

        try {
            assertFailsWith<RuntimeException> {
                negotiate(options)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate v1 requires connection token`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "negotiateVersion": 1,
                          "connectionId": "public-id",
                          "availableTransports": [
                            {
                              "transport": "WebSockets",
                              "transferFormats": ["Binary"]
                            }
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Binary,
        )

        try {
            assertFailsWith<RuntimeException> {
                negotiate(options)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate rejects aspnet signalr response`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"ProtocolVersion":"1.5"}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Binary,
        )

        try {
            assertFailsWith<RuntimeException> {
                negotiate(options)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate does not duplicate existing negotiate version query`() = runTest {
        lateinit var requestedUrl: String
        val client = HttpClient(
            MockEngine { request ->
                requestedUrl = request.url.toString()
                respond(
                    content = """
                        {
                          "negotiateVersion": 1,
                          "connectionId": "public-id",
                          "connectionToken": "secret-token",
                          "availableTransports": [
                            {
                              "transport": "WebSockets",
                              "transferFormats": ["Binary"]
                            }
                          ]
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val options = testOptions(
            client = client,
            baseUrl = "https://example.com/hub?negotiateVersion=0&x=1",
            transferFormat = TransferFormat.Binary,
        )

        try {
            negotiate(options)

            assertTrue(requestedUrl.contains("negotiateVersion=0"))
            assertEquals(1, Regex("negotiateVersion=").findAll(requestedUrl).count())
        } finally {
            client.close()
        }
    }

    @Test
    fun `negotiate redirect replaces access token for redirected requests and transport`() = runTest {
        val authorizationHeaders = mutableListOf<String?>()
        val client = HttpClient(
            MockEngine { request ->
                authorizationHeaders += request.headers[HttpHeaders.Authorization]
                when (authorizationHeaders.size) {
                    1 -> respond(
                        content = """{"url":"https://redirect.example/hub","accessToken":"redirect-token"}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                    else -> respond(
                        content = """
                            {
                              "negotiateVersion": 1,
                              "connectionId": "public-id",
                              "connectionToken": "secret-token",
                              "availableTransports": [
                                {
                                  "transport": "WebSockets",
                                  "transferFormats": ["Binary"]
                                }
                              ]
                            }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            },
        )
        val options = testOptions(
            client = client,
            transferFormat = TransferFormat.Binary,
            accessTokenProvider = { "provider-token" },
        )

        try {
            val negotiation = negotiate(options)

            assertEquals(
                listOf<String?>("Bearer provider-token", "Bearer redirect-token"),
                authorizationHeaders,
            )
            assertEquals("Bearer redirect-token", negotiation.headers[HttpHeaders.Authorization])
            assertEquals("https://redirect.example/hub?id=secret-token", negotiation.url)
        } finally {
            client.close()
        }
    }

    @Test
    fun `non OK negotiate response fails`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "nope",
                    status = HttpStatusCode.InternalServerError,
                )
            },
        )
        val options = testOptions(client = client)

        try {
            val exception = assertFailsWith<RuntimeException> {
                negotiate(options)
            }

            assertTrue(exception.message?.contains("Unexpected status code returned from negotiate") == true)
        } finally {
            client.close()
        }
    }

    private fun testOptions(
        client: HttpClient,
        baseUrl: String = "https://example.com/hub",
        transferFormat: TransferFormat = TransferFormat.Binary,
        headers: Map<String, String> = emptyMap(),
        accessTokenProvider: (suspend () -> String)? = null,
        skipNegotiate: Boolean = false,
    ) = HubConnectionOptions.create(
        url = baseUrl,
        httpClient = client,
        config = HubConnectionConfig().apply {
            protocol = TestHubProtocol(transferFormat)
            this.headers = headers
            this.accessTokenProvider = accessTokenProvider
            this.skipNegotiate = skipNegotiate
            json = Json
        },
    )

    private class TestHubProtocol(
        override val transferFormat: TransferFormat,
    ) : HubProtocol {
        override val name: String = "test"
        override val version: Int = 1

        override fun parseMessages(
            payload: ByteArray,
        ): List<HubMessage> = emptyList()

        override fun writeMessage(
            message: HubMessage,
        ): ByteArray = ByteArray(0)
    }

    private data class NegotiationUrlCase(
        val baseUrl: String,
        val negotiateUrl: String,
    )

    private fun successfulNegotiationResponse(): String = """
        {
          "connectionId": "public-id",
          "availableTransports": [
            {
              "transport": "WebSockets",
              "transferFormats": ["Text", "Binary"]
            }
          ]
        }
    """.trimIndent()
}
