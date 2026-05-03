package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerEnvApiSubscriptionsTest {
    @Test
    fun `subscription methods send expected requests and decode storage stats`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)

        val account = env.api.accounts.subscription(
            httpClient = client,
            env = env,
            token = token,
        )
        val organization = env.api.organizations.subscription(
            httpClient = client,
            env = env,
            token = token,
            organizationId = "organization-1",
        )

        assertEquals(
            listOf(
                RecordedRequest(HttpMethod.Get, "$baseApiUrl/accounts/subscription", "get-account-subscription"),
                RecordedRequest(
                    HttpMethod.Get,
                    "$baseApiUrl/organizations/organization-1/subscription",
                    "get-organization-subscription",
                ),
            ),
            requests.map { it.copy(authorization = null, body = "") },
        )
        requests.forEach { request ->
            assertEquals("Bearer $token", request.authorization)
        }

        assertEquals("subscription", account.obj)
        assertEquals("12.34 MB", account.storageName)
        assertEquals(0.01, account.storageGb)
        assertEquals(1, account.maxStorageGb)

        assertEquals("organizationSubscription", organization.obj)
        assertEquals("2 GB", organization.storageName)
        assertEquals(2.0, organization.storageGb)
        assertEquals(10, organization.maxStorageGb)
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
    ) = HttpClient(
        MockEngine { request ->
            requests += RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                route = request.attributes.getOrNull(routeAttribute),
                authorization = request.headers[HttpHeaders.Authorization],
                body = request.body.asText(),
            )
            respond(
                content = responseBodyForRoute(request.attributes.getOrNull(routeAttribute)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(apiJson))
        }
    }

    private fun responseBodyForRoute(route: String?) = when (route) {
        "get-account-subscription" -> accountSubscriptionResponse
        "get-organization-subscription" -> organizationSubscriptionResponse
        else -> error("Unexpected route: $route")
    }

    private fun OutgoingContent.asText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unsupported request body type: ${this::class}")
    }

    private data class RecordedRequest(
        val method: HttpMethod,
        val url: String,
        val route: String?,
        val authorization: String? = null,
        val body: String = "",
    )

    private companion object {
        const val baseApiUrl = "https://vault.example.com/api"
        val env = ServerEnv(baseUrl = "https://vault.example.com")
        const val token = "access-token"

        val apiJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        val accountSubscriptionResponse = """
            {
              "object": "subscription",
              "storageName": "12.34 MB",
              "storageGb": 0.01,
              "maxStorageGb": 1,
              "subscription": {
                "status": "active"
              }
            }
        """.trimIndent()

        val organizationSubscriptionResponse = """
            {
              "Object": "organizationSubscription",
              "StorageName": "2 GB",
              "StorageGb": 2.0,
              "MaxStorageGb": 10,
              "Subscription": {
                "status": "active"
              }
            }
        """.trimIndent()
    }
}
