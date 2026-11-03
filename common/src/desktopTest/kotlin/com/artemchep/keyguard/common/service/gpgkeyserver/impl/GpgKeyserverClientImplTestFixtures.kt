package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.gpgKeyIdFromFingerprintOrNull
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headers

internal const val TEST_FINGERPRINT = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
internal val TEST_KEY_ID = TEST_FINGERPRINT.takeLast(16)

internal val TEST_HKP_CONFIG = GpgKeyserverConfig(
    url = GpgKeyserverConfig.HKP_UBUNTU_URL,
    protocol = GpgKeyserverConfig.Protocol.HKP,
)

internal fun recordingClient(
    requests: MutableList<RecordedRequest>,
    response: String,
    contentType: ContentType,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpClient = HttpClient(
    MockEngine { request ->
        requests += RecordedRequest(
            method = request.method,
            pathSegments = request.url.segments,
            route = request.attributes.getOrNull(routeAttribute),
            query = request.url.parameters.entries().associate { (key, values) ->
                key to values.single()
            },
            body = request.body.asText(),
            contentType = request.body.contentType,
        )
        respond(
            content = response,
            status = status,
            headers = headers {
                append(HttpHeaders.ContentType, contentType.toString())
            },
        )
    },
)

internal data class RecordedRequest(
    val method: HttpMethod,
    val pathSegments: List<String>,
    val route: String?,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val contentType: ContentType? = null,
)

private fun OutgoingContent.asText(): String = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    is OutgoingContent.NoContent -> ""
    is TextContent -> text
    else -> error("Unsupported outgoing content: ${this::class}")
}

internal class FakeParser(
    private val result: GpgPublicKeyParseResult = GpgPublicKeyParseResult.Success(emptyList()),
) : GpgPublicKeyParser {
    val armoredInputs = mutableListOf<String>()

    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult {
        armoredInputs += armored
        return result
    }
}

internal fun keyInfo(
    fingerprint: String,
    userIds: List<String>,
    emails: List<String>,
) = GpgPublicKeyInfo(
    fingerprint = fingerprint,
    keyId = requireNotNull(fingerprint.gpgKeyIdFromFingerprintOrNull()),
    algorithm = "ED25519",
    bitStrength = null,
    userIds = userIds,
    emails = emails,
    createdAt = null,
    expiresAt = null,
    revoked = false,
    canSign = true,
    canEncrypt = false,
    publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
    subKeys = emptyList(),
)

internal fun singleKeyParser() = FakeParser(
    result = GpgPublicKeyParseResult.Success(
        keys = listOf(
            keyInfo(
                fingerprint = TEST_FINGERPRINT,
                userIds = listOf("Alice Example <alice@example.com>"),
                emails = listOf("alice@example.com"),
            ),
        ),
    ),
)

/** Runs [request] against a [recordingClient] that records into [requests]. */
internal suspend fun searchRecording(
    requests: MutableList<RecordedRequest>,
    request: SearchGpgPublicKeyRequest,
    config: GpgKeyserverConfig = GpgKeyserverConfig(),
    response: String = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
    contentType: ContentType = ContentType.parse("application/pgp-keys"),
    parser: GpgPublicKeyParser = singleKeyParser(),
): List<DGpgKeyserverResult> {
    val client = recordingClient(
        requests = requests,
        response = response,
        contentType = contentType,
    )
    return GpgKeyserverClientImpl(client, parser)
        .search(
            request = request,
            config = config,
        )
        .bind()
}
