package com.artemchep.keyguard.common.service.gpgkeyserver.impl

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
