package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SyncEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal class BitwardenSyncV2TestServer(
    private val installRetryPolicy: Boolean = false,
) {
    val env = ServerEnv(baseUrl = BASE_URL)
    val token = "access-token"
    val requests = mutableListOf<RecordedBitwardenRequest>()
    val retryDelays = mutableListOf<Long>()
    val folders = linkedMapOf<String, FolderEntity>()
    var syncFoldersOverride: List<FolderEntity>? = null

    var revisionDate: String = "2024-01-01T00:00:00Z"
    var revisionDateFailure: TestHttpFailure? = null
    var syncFailure: TestHttpFailure? = null
    val syncFailures = ArrayDeque<TestHttpFailure>()
    var syncException: Throwable? = null
    var nextFolderPutFailure: TestHttpFailure? = null
    val folderPutFailures = ArrayDeque<TestHttpFailure>()

    private var nextFolderIndex = 1

    val client = HttpClient(
        MockEngine { request ->
            val body = request.body.asText()
            val recorded =
                RecordedBitwardenRequest(
                    method = request.method,
                    url = request.url.toString(),
                    route = request.attributes.getOrNull(routeAttribute),
                    authorization = request.headers[HttpHeaders.Authorization],
                    body = body,
                )
            requests += recorded

            handle(recorded)
        },
    ) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(json))
        }
        if (installRetryPolicy) {
            install(HttpRequestRetry) {
                configureBitwardenHttpRetry { retryDelayMillis ->
                    retryDelays += retryDelayMillis
                }
            }
        }
    }

    fun seedFolder(
        id: String,
        name: String,
        revisionDate: Instant = INSTANT_0,
    ): FolderEntity {
        val folder = FolderEntity(
            id = id,
            name = name,
            revisionDate = revisionDate,
        )
        folders[id] = folder
        return folder
    }

    fun clearRequests() {
        requests.clear()
    }

    fun bumpRevision(suffix: String = folders.values.maxOfOrNull { it.revisionDate.toString() } ?: revisionDate) {
        revisionDate = suffix
    }

    private fun MockRequestHandleScope.handle(request: RecordedBitwardenRequest) = when {
        request.method == HttpMethod.Get &&
            request.path == "/api/accounts/revision-date" -> {
            val failure = revisionDateFailure
            if (failure != null) {
                failureResponse(failure)
            } else {
                respondText(
                    content = revisionDate,
                    contentType = ContentType.Text.Plain,
                )
            }
        }

        request.method == HttpMethod.Get &&
            request.path == "/api/sync" -> {
            val exception = syncException
            if (exception != null) {
                throw exception
            }
            val failure = syncFailures.removeFirstOrNullCompat()
                ?: syncFailure
            if (failure != null) {
                failureResponse(failure)
            } else {
                respondJson(
                    SyncEntity(
                        profile = testProfile,
                        folders = syncFoldersOverride ?: folders.values.toList(),
                        ciphers = emptyList(),
                        collections = emptyList(),
                        sends = emptyList(),
                    ),
                )
            }
        }

        request.method == HttpMethod.Post &&
            request.path == "/api/folders/" -> {
            val body = json.decodeFromString<FolderRequest>(request.body)
            val remoteId = "folder-created-${nextFolderIndex++}"
            val folder = FolderEntity(
                id = remoteId,
                name = body.name,
                revisionDate = INSTANT_4,
            )
            folders[remoteId] = folder
            revisionDate = folder.revisionDate.toString()
            respondJson(folder)
        }

        request.method == HttpMethod.Put &&
            request.path.startsWith("/api/folders/") -> {
            val failure = nextFolderPutFailure
                ?: folderPutFailures.removeFirstOrNullCompat()
            if (failure != null) {
                nextFolderPutFailure = null
                failureResponse(failure)
            } else {
                val id = request.path.removePrefix("/api/folders/")
                val existing = folders[id]
                if (existing == null) {
                    failureResponse(
                        TestHttpFailure(
                            status = HttpStatusCode.NotFound,
                            body = """{"error":{"description":"Folder not found"}}""",
                        ),
                    )
                } else {
                    val body = json.decodeFromString<FolderRequest>(request.body)
                    val folder = existing.copy(
                        name = body.name,
                        revisionDate = INSTANT_4,
                    )
                    folders[id] = folder
                    revisionDate = folder.revisionDate.toString()
                    respondJson(folder)
                }
            }
        }

        request.method == HttpMethod.Delete &&
            request.path.startsWith("/api/folders/") -> {
            val id = request.path.removePrefix("/api/folders/")
            folders.remove(id)
            revisionDate = INSTANT_4.toString()
            respondText("")
        }

        else -> failureResponse(
            TestHttpFailure(
                status = HttpStatusCode.NotFound,
                body = "<html>not found</html>",
                contentType = ContentType.Text.Html,
            ),
        )
    }

    private inline fun <reified T : Any> MockRequestHandleScope.respondJson(body: T) =
        respondText(
            content = json.encodeToString(body),
            contentType = ContentType.Application.Json,
        )

    private fun MockRequestHandleScope.respondText(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: ContentType = ContentType.Text.Plain,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = respond(
        content = content,
        status = status,
        headers = responseHeaders(contentType, extraHeaders),
    )

    private fun MockRequestHandleScope.failureResponse(failure: TestHttpFailure) =
        respondText(
            content = failure.body,
            status = failure.status,
            contentType = failure.contentType,
            extraHeaders = failure.headers,
        )

    private fun responseHeaders(
        contentType: ContentType,
        extraHeaders: Map<String, String>,
    ): Headers {
        val builder = HeadersBuilder()
        builder.append(HttpHeaders.ContentType, contentType.toString())
        extraHeaders.forEach { (name, value) ->
            builder.append(name, value)
        }
        return builder.build()
    }

    private fun OutgoingContent.asText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unsupported request body type: ${this::class}")
    }

    internal data class RecordedBitwardenRequest(
        val method: HttpMethod,
        val url: String,
        val route: String?,
        val authorization: String?,
        val body: String,
    ) {
        val path: String
            get() = url.substringAfter(BASE_URL).substringBefore('?')
    }

    internal data class TestHttpFailure(
        val status: HttpStatusCode,
        val body: String,
        val contentType: ContentType = ContentType.Application.Json,
        val headers: Map<String, String> = emptyMap(),
    )

    companion object {
        const val BASE_URL = "https://vault.example.com"

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        val INSTANT_0 = Instant.parse("2024-01-01T00:00:00Z")
        val INSTANT_1 = Instant.parse("2024-01-01T00:00:01Z")
        val INSTANT_2 = Instant.parse("2024-01-01T00:00:02Z")
        val INSTANT_4 = Instant.parse("2024-01-01T00:00:04Z")

        private val testProfile = ProfileEntity(
            culture = "en-US",
            email = "user@example.com",
            emailVerified = true,
            id = "profile-1",
            key = "profile-key",
            privateKey = "profile-private-key",
            obj = "profile",
            organizations = emptyList(),
            premium = true,
            securityStamp = "security-stamp-1",
            twoFactorEnabled = false,
        )
    }
}

private fun <T> ArrayDeque<T>.removeFirstOrNullCompat(): T? =
    if (isEmpty()) {
        null
    } else {
        removeFirst()
    }
