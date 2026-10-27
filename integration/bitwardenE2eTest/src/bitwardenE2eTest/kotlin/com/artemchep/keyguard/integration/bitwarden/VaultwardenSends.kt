package com.artemchep.keyguard.integration.bitwarden

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Anonymous Send access, the way a recipient opens a Send link. */
internal class VaultwardenSends(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
    /**
     * A Send access token, valid for 2 minutes. Throws with the response body on failure:
     * 404 `send_id_invalid` or `password_hash_b64_invalid`, 400 `password_hash_b64_required`.
     */
    suspend fun accessToken(accessId: String, passwordHashB64: String? = null): String = httpClient
        .submitForm(
            url = "$baseUrl/identity/connect/token",
            formParameters = parameters {
                append("grant_type", "send_access")
                append("client_id", "keyguard-e2e")
                append("send_id", accessId)
                if (passwordHashB64 != null) {
                    append("password_hash_b64", passwordHashB64)
                }
            },
        )
        .requireJson("Get a Send access token for $accessId")
        .jsonObject
        .string("access_token")

    /** The Send, with its fields still encrypted with the Send key. */
    suspend fun access(token: String): JsonObject = httpClient
        .post("$baseUrl/api/sends/access") {
            bearerAuth(token)
        }
        .requireJson("Access a Send")
        .jsonObject

    /** The encrypted file of a file Send. */
    suspend fun accessFile(token: String, fileId: String): ByteArray {
        val url = httpClient
            .post("$baseUrl/api/sends/access/file/$fileId") {
                bearerAuth(token)
            }
            .requireJson("Access the Send file $fileId")
            .jsonObject
            .string("url")
        return httpClient
            .get(url)
            .requireBytes("Download the Send file $fileId")
    }
}

private suspend fun HttpResponse.requireBytes(action: String): ByteArray {
    if (!status.isSuccess()) {
        requireSuccess(action)
    }
    return bodyAsBytes()
}
