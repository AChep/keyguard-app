package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.AvatarRequestEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileRequestEntity
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerEnvApiAccountsTest {
    @Test
    fun `account profile methods decode profile responses`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)
        val api = env.api.accounts

        val avatar = api.avatar(
            httpClient = client,
            env = env,
            token = token,
            model = AvatarRequestEntity(avatarColor = "#336699"),
        )
        val profile = api.profile(
            httpClient = client,
            env = env,
            token = token,
            model = ProfileRequestEntity(
                culture = "en-US",
                name = "Main Vault",
                masterPasswordHint = null,
            ),
        )

        assertEquals(
            listOf(
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/accounts/avatar", "put-avatar"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/accounts/profile", "put-profile"),
            ),
            requests.map { it.copy(authorization = null, body = "") },
        )
        requests.forEach { request ->
            assertEquals("Bearer $token", request.authorization)
        }
        assertEquals("#336699", requests[0].json["avatarColor"]!!.jsonPrimitive.content)
        assertEquals("Main Vault", requests[1].json["name"]!!.jsonPrimitive.content)

        assertEquals("profile", avatar.obj)
        assertEquals("user-1", avatar.id)
        assertEquals("#336699", avatar.avatarColor)
        assertEquals("profile", profile.obj)
        assertEquals("Main Vault", profile.name)
        assertEquals("security-stamp-1", profile.securityStamp)
    }

    @Test
    fun `account revision date decodes string response`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            responseContent = "rev-1",
        )

        val revisionDate = env.api.accounts.revisionDate(
            httpClient = client,
            env = env,
            token = token,
        )

        assertEquals("rev-1", revisionDate)
        assertEquals(
            listOf(
                RecordedRequest(
                    HttpMethod.Get,
                    "$baseApiUrl/accounts/revision-date",
                    "get-accounts-revision-date",
                ),
            ),
            requests.map { it.copy(authorization = null, body = "") },
        )
        assertEquals("Bearer $token", requests.single().authorization)
    }

    @Test
    fun `account revision date surfaces parsed api error`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(
            requests = requests,
            responseContent = revisionDateErrorResponse,
            status = HttpStatusCode.BadRequest,
        )

        val error = assertFailsWith<ApiException> {
            env.api.accounts.revisionDate(
                httpClient = client,
                env = env,
                token = token,
            )
        }

        assertEquals("Detailed revision failure", error.message)
    }

    private fun recordingClient(
        requests: MutableList<RecordedRequest>,
        responseContent: String = profileResponse,
        status: HttpStatusCode = HttpStatusCode.OK,
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
                content = responseContent,
                status = status,
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

        val profileResponse = """
            {
              "object": "profile",
              "id": "user-1",
              "name": "Main Vault",
              "email": "user@example.com",
              "emailVerified": true,
              "premium": false,
              "premiumFromOrganization": false,
              "culture": "en-US",
              "twoFactorEnabled": true,
              "key": "user-key",
              "privateKey": "private-key",
              "securityStamp": "security-stamp-1",
              "forcePasswordReset": false,
              "usesKeyConnector": false,
              "avatarColor": "#336699",
              "creationDate": "2024-01-01T00:00:00Z",
              "verifyDevices": true,
              "organizations": [],
              "providers": [],
              "providerOrganizations": []
            }
        """.trimIndent()

        val revisionDateErrorResponse = """
            {
              "error": "invalid_revision",
              "errorModel": {
                "message": "Detailed revision failure"
              }
            }
        """.trimIndent()
    }
}
