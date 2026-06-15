package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.CipherRepromptTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherArchiveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherBulkShareRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherBulkUpdateCollectionsRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherDeleteRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherMoveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRestoreRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherUnarchiveRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherWithIdRequest
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerEnvApiCipherBulkTest {
    @Test
    fun `cipher bulk methods send expected requests`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = recordingClient(requests)
        val api = env.api.ciphers

        val archived = api.archive(client, env, token, CipherArchiveRequest(ids = listOf("cipher-archive")))
        val unarchived = api.unarchive(client, env, token, CipherUnarchiveRequest(ids = listOf("cipher-unarchive")))
        api.trash(client, env, token, CipherDeleteRequest(ids = listOf("cipher-trash")))
        api.delete(client, env, token, CipherDeleteRequest(ids = listOf("cipher-delete")))
        val restored = api.restore(client, env, token, CipherRestoreRequest(ids = listOf("cipher-restore")))
        api.move(
            httpClient = client,
            env = env,
            token = token,
            body = CipherMoveRequest(
                ids = listOf("cipher-move"),
                folderId = "folder-1",
            ),
        )
        val shared = api.share(
            httpClient = client,
            env = env,
            token = token,
            body = CipherBulkShareRequest(
                collectionIds = listOf("collection-1"),
                ciphers = listOf(createCipherRequest()),
            ),
        )
        api.bulkCollections(
            httpClient = client,
            env = env,
            token = token,
            body = CipherBulkUpdateCollectionsRequest(
                organizationId = "org-1",
                cipherIds = listOf("cipher-collection"),
                collectionIds = listOf("collection-1", "collection-2"),
                removeCollections = true,
            ),
        )

        assertEquals(
            listOf(
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/archive", "put-archive-ciphers"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/unarchive", "put-unarchive-ciphers"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/delete", "put-delete-ciphers"),
                RecordedRequest(HttpMethod.Delete, "$baseApiUrl/ciphers/", "delete-ciphers"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/restore", "put-restore-ciphers"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/move", "put-move-ciphers"),
                RecordedRequest(HttpMethod.Put, "$baseApiUrl/ciphers/share", "put-share-ciphers"),
                RecordedRequest(HttpMethod.Post, "$baseApiUrl/ciphers/bulk-collections", "post-bulk-cipher-collections"),
            ),
            requests.map { it.copy(authorization = null, body = "") },
        )
        requests.forEach { request ->
            assertEquals("Bearer $token", request.authorization)
        }

        assertEquals("cipher-archive", requests[0].json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cipher-unarchive", requests[1].json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cipher-trash", requests[2].json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cipher-delete", requests[3].json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("cipher-restore", requests[4].json["ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("folder-1", requests[5].json["folderId"]!!.jsonPrimitive.content)
        assertEquals("collection-1", requests[6].json["collectionIds"]!!.jsonArray[0].jsonPrimitive.content)
        val shareCipher = requests[6].json["ciphers"]!!.jsonArray[0].jsonObject
        assertEquals("cipher-share", shareCipher["id"]!!.jsonPrimitive.content)
        assertEquals("org-1", shareCipher["organizationId"]!!.jsonPrimitive.content)
        assertEquals("Cipher", shareCipher["name"]!!.jsonPrimitive.content)
        assertEquals("org-1", requests[7].json["organizationId"]!!.jsonPrimitive.content)
        assertEquals("cipher-collection", requests[7].json["cipherIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("collection-2", requests[7].json["collectionIds"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals(true, requests[7].json["removeCollections"]!!.jsonPrimitive.boolean)

        assertEquals("cipher-response-1", archived.data.single().id)
        assertEquals("folder-1", archived.data.single().folderId)
        assertEquals("cipher-response-1", unarchived.data.single().id)
        assertEquals("cipher-mini-1", restored.data.single().id)
        assertEquals(null, restored.data.single().folderId)
        assertEquals("cipher-mini-1", shared.data.single().id)
    }

    @Test
    fun `cipher bulk hard delete validates non-success response`() = runTest {
        val client = failingClient(
            route = "delete-ciphers",
            status = HttpStatusCode.Forbidden,
        )

        val error = assertFailsWith<HttpException> {
            env.api.ciphers.delete(
                httpClient = client,
                env = env,
                token = token,
                body = CipherDeleteRequest(ids = listOf("cipher-delete")),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, error.statusCode)
    }

    @Test
    fun `cipher single hard delete validates non-success response`() = runTest {
        val client = failingClient(
            route = "delete-cipher",
            status = HttpStatusCode.InternalServerError,
        )

        val error = assertFailsWith<HttpException> {
            env.api.ciphers.focus("cipher-delete").delete(
                httpClient = client,
                env = env,
                token = token,
            )
        }

        assertEquals(HttpStatusCode.InternalServerError, error.statusCode)
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

    private fun failingClient(
        route: String,
        status: HttpStatusCode,
    ) = HttpClient(
        MockEngine { request ->
            val responseStatus =
                if (request.attributes.getOrNull(routeAttribute) == route) {
                    status
                } else {
                    HttpStatusCode.OK
                }
            respond(
                content = """{"message":"hard delete rejected"}""",
                status = responseStatus,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(apiJson))
        }
    }

    private fun responseBodyForRoute(route: String?) = when (route) {
        "put-archive-ciphers",
        "put-unarchive-ciphers",
        -> fullCipherListResponse

        "put-restore-ciphers",
        "put-share-ciphers",
        -> miniCipherListResponse

        else -> ""
    }

    private fun OutgoingContent.asText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unsupported request body type: ${this::class}")
    }

    private val RecordedRequest.json: JsonObject
        get() = apiJson.parseToJsonElement(body).jsonObject

    private fun createCipherRequest() = CipherWithIdRequest(
        id = "cipher-share",
        key = null,
        type = CipherTypeEntity.SecureNote,
        organizationId = "org-1",
        folderId = null,
        name = "Cipher",
        notes = null,
        favorite = false,
        login = null,
        secureNote = null,
        card = null,
        identity = null,
        sshKey = null,
        fields = null,
        passwordHistory = null,
        attachments = null,
        attachments2 = null,
        lastKnownRevisionDate = null,
        archivedDate = null,
        reprompt = CipherRepromptTypeEntity.None,
    )

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

        val fullCipherListResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "cipher",
                  "id": "cipher-response-1",
                  "type": 2,
                  "data": "{}",
                  "organizationId": null,
                  "folderId": "folder-1",
                  "favorite": true,
                  "edit": true,
                  "viewPassword": true,
                  "revisionDate": "2024-01-01T00:00:00Z",
                  "creationDate": "2024-01-01T00:00:00Z",
                  "organizationUseTotp": false
                }
              ]
            }
        """.trimIndent()

        val miniCipherListResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "cipherMini",
                  "id": "cipher-mini-1",
                  "type": 2,
                  "data": "{}",
                  "organizationId": null,
                  "revisionDate": "2024-01-02T00:00:00Z",
                  "creationDate": "2024-01-01T00:00:00Z",
                  "organizationUseTotp": false
                }
              ]
            }
        """.trimIndent()
    }
}
