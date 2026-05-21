package com.artemchep.keyguard.common.service.hibp.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HibpRepositoryImplTest {
    @Test
    fun `subscription status sends token, user agent, and route`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = MockResponse(
                status = HttpStatusCode.OK,
                body = subscriptionStatusBody,
            ),
        )

        HibpRepositoryImpl(client)
            .getSubscriptionStatus(apiToken = apiToken)
            .bind()

        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://haveibeenpwned.com/api/v3/subscription/status",
                    apiToken = apiToken,
                    userAgent = "Keyguard",
                    route = HibpRepositoryImpl.ROUTE_GET_SUBSCRIPTION_STATUS,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `subscription status propagates HIBP HTTP errors`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = MockResponse(
                status = HttpStatusCode.Unauthorized,
                body = errorBody,
            ),
        )

        try {
            HibpRepositoryImpl(client)
                .getSubscriptionStatus(apiToken = apiToken)
                .bind()
            fail("Expected HIBP API token check to fail.")
        } catch (e: HttpException) {
            assertEquals(HttpStatusCode.Unauthorized, e.statusCode)
        }
    }

    @Test
    fun `breached account sends token, flags, user agent, and route`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = MockResponse(
                status = HttpStatusCode.OK,
                body = breachesBody,
            ),
        )

        val breaches = HibpRepositoryImpl(client)
            .getBreachedAccount(
                username = username,
                apiToken = apiToken,
            )
            .bind()

        assertEquals(1, breaches.size)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://haveibeenpwned.com/api/v3/breachedaccount/$username?truncateResponse=false&includeUnverified=false",
                    apiToken = apiToken,
                    userAgent = "Keyguard",
                    route = HibpRepositoryImpl.ROUTE_GET_BREACHED_ACCOUNT,
                    truncateResponse = "false",
                    includeUnverified = "false",
                ),
            ),
            requests,
        )
    }

    @Test
    fun `breached account returns empty list for not found`() = runTest {
        val client = recordingClient(
            requests = mutableListOf(),
            response = MockResponse(
                status = HttpStatusCode.NotFound,
                body = errorBody,
            ),
        )

        val breaches = HibpRepositoryImpl(client)
            .getBreachedAccount(
                username = username,
                apiToken = apiToken,
            )
            .bind()

        assertTrue(breaches.isEmpty())
    }

    @Test
    fun `breached account retries 429 using retry after header`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val retryDelays = mutableListOf<Long>()
        val client = recordingClient(
            requests = requests,
            responses = listOf(
                MockResponse(
                    status = HttpStatusCode.TooManyRequests,
                    body = errorBody,
                    headers = mapOf(HttpHeaders.RetryAfter to "3"),
                ),
                MockResponse(
                    status = HttpStatusCode.OK,
                    body = breachesBody,
                ),
            ),
            installRetryPolicy = true,
            retryDelays = retryDelays,
        )

        val breaches = HibpRepositoryImpl(client)
            .getBreachedAccount(
                username = username,
                apiToken = apiToken,
            )
            .bind()

        assertEquals(1, breaches.size)
        assertEquals(listOf(3_000L), retryDelays)
        assertEquals(2, requests.size)
        assertTrue(
            requests.all { request ->
                request.route == HibpRepositoryImpl.ROUTE_GET_BREACHED_ACCOUNT &&
                        request.includeUnverified == "false" &&
                        request.truncateResponse == "false"
            },
        )
    }

    @Test
    fun `all breaches calls HIBP breaches endpoint and decodes group`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            response = MockResponse(
                status = HttpStatusCode.OK,
                body = breachesBody,
            ),
        )

        val group = HibpRepositoryImpl(client)
            .getBreaches()
            .bind()

        assertEquals(1, group.breaches.size)
        assertEquals("Adobe", group.breaches.first().name)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://haveibeenpwned.com/api/v3/breaches",
                    userAgent = "Keyguard",
                    route = HibpRepositoryImpl.ROUTE_GET_BREACHES,
                ),
            ),
            requests,
        )
    }

    @Test
    fun `pwned password range returns matching suffix count or zero`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            responses = listOf(
                MockResponse(
                    status = HttpStatusCode.OK,
                    body = """
                        1234567890:42
                        FFFFFFFFFF:7
                    """.trimIndent(),
                    contentType = ContentType.Text.Plain,
                ),
                MockResponse(
                    status = HttpStatusCode.OK,
                    body = "FFFFFFFFFF:7",
                    contentType = ContentType.Text.Plain,
                ),
            ),
        )
        val repository = HibpRepositoryImpl(client)

        val matching = repository
            .getPwnedPasswordOccurrences("ABCDE1234567890")
            .bind()
        val missing = repository
            .getPwnedPasswordOccurrences("ABCDE0000000000")
            .bind()

        assertEquals(42, matching)
        assertEquals(0, missing)
        assertEquals(
            listOf(
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://api.pwnedpasswords.com/range/ABCDE",
                    userAgent = "Keyguard",
                    route = HibpRepositoryImpl.ROUTE_GET_PWNED_PASSWORD_RANGE,
                ),
                RecordedRequest(
                    method = HttpMethod.Get,
                    url = "https://api.pwnedpasswords.com/range/ABCDE",
                    userAgent = "Keyguard",
                    route = HibpRepositoryImpl.ROUTE_GET_PWNED_PASSWORD_RANGE,
                ),
            ),
            requests,
        )
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        response: MockResponse,
    ) = recordingClient(
        requests = requests,
        responses = listOf(response),
    )

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        responses: List<MockResponse>,
        installRetryPolicy: Boolean = false,
        retryDelays: MutableList<Long>? = null,
    ): HttpClient {
        val remainingResponses = ArrayDeque<MockResponse>().apply {
            addAll(responses)
        }
        return HttpClient(
            MockEngine { request ->
                requests += RecordedRequest(
                    method = request.method,
                    url = request.url.toString(),
                    apiToken = request.headers["hibp-api-key"],
                    userAgent = request.headers[HttpHeaders.UserAgent],
                    route = request.attributes.getOrNull(routeAttribute),
                    truncateResponse = request.url.parameters["truncateResponse"],
                    includeUnverified = request.url.parameters["includeUnverified"],
                )
                val response = remainingResponses.removeFirst()
                val responseHeaders = headers {
                    append(HttpHeaders.ContentType, response.contentType.toString())
                    response.headers.forEach { (name, value) ->
                        append(name, value)
                    }
                }
                respond(
                    content = response.body,
                    status = response.status,
                    headers = responseHeaders,
                )
            },
        ) {
            install(ContentNegotiation) {
                register(ContentType.Application.Json, KotlinxSerializationConverter(apiJson))
            }
            if (installRetryPolicy) {
                install(HttpRequestRetry) {
                    if (retryDelays != null) {
                        configureBitwardenHttpRetry { retryDelayMillis ->
                            retryDelays.add(retryDelayMillis)
                        }
                    } else {
                        configureBitwardenHttpRetry()
                    }
                }
            }
        }
    }

    private data class MockResponse(
        val status: HttpStatusCode,
        val body: String,
        val contentType: ContentType = ContentType.Application.Json,
        val headers: Map<String, String> = emptyMap(),
    )

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val apiToken: String? = null,
        val userAgent: String?,
        val route: String?,
        val truncateResponse: String? = null,
        val includeUnverified: String? = null,
    )

    private companion object {
        const val apiToken = "00000000000000000000000000000000"
        const val username = "alice"

        val apiJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        val subscriptionStatusBody = """
            {
              "SubscriptionName": "Core",
              "Description": "Test subscription",
              "SubscribedUntil": "2026-01-01T00:00:00Z",
              "Rpm": 10,
              "DomainSearchMaxBreachedAccounts": 0,
              "MaxBreachedDomains": 0,
              "IncludesStealerLogs": false,
              "IncludesBulkDomainAdd": false,
              "IncludesAutoSubdomainVerification": false,
              "IncludesCustomerDomains": false,
              "IncludesKAnon": false
            }
        """.trimIndent()

        val breachesBody = """
            [
              {
                "Name": "Adobe",
                "Title": "Adobe",
                "Domain": "adobe.com",
                "BreachDate": "2013-10-04",
                "AddedDate": "2013-12-04",
                "PwnCount": 152445165,
                "Description": "Adobe breach.",
                "LogoPath": "https://haveibeenpwned.com/Content/Images/PwnedLogos/Adobe.png",
                "DataClasses": ["Email addresses", "Password hints"],
                "IsVerified": true
              }
            ]
        """.trimIndent()

        val errorBody = """
            {
              "message": "Access denied due to invalid hibp-api-key."
            }
        """.trimIndent()
    }
}
