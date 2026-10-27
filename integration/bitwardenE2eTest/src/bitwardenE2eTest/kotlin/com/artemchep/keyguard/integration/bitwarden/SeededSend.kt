package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKeyMaterial
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** A Send created over the API, with the client-side secrets a recipient link carries. */
internal class SeededSend(
    val id: String,
    val accessId: String,
    /** The 16-byte key material; the server stores it under the user key, a share link in base64url. */
    val material: ByteArray,
    /** 64 bytes, derived from [material]; encrypts the Send's fields and file. */
    val key: ByteArray,
    /** What a recipient sends as `password_hash_b64`; null without a password. */
    val passwordHashB64: String?,
)

/** Creates a text Send, the way a Bitwarden client does. */
internal suspend fun VaultwardenApi.createTextSend(
    account: TestAccount,
    accessToken: String,
    text: String,
    password: String? = null,
): SeededSend {
    val material = cryptoGenerator.makeSendCryptoKeyMaterial()
    val key = cryptoGenerator.makeSendCryptoKey(material)
    val passwordHash = password?.let { sendPasswordHash(it, material) }
    val send = httpClient
        .post("$baseUrl/api/sends") {
            bearerAuth(accessToken)
            setJsonBody(
                sendBody(account, material, key, type = SEND_TYPE_TEXT, passwordHash = passwordHash) {
                    putJsonObject("text") {
                        put("text", accounts.encrypt(key, text))
                        put("hidden", false)
                    }
                },
            )
        }
        .requireJson("Create a text Send")
        .jsonObject
    return SeededSend(send.string("id"), send.string("accessId"), material, key, passwordHash)
}

/** Creates a file Send and uploads [content] as an [EncryptedFileFrame]. */
internal suspend fun VaultwardenApi.createFileSend(
    account: TestAccount,
    accessToken: String,
    fileName: String,
    content: ByteArray,
): SeededSend {
    val material = cryptoGenerator.makeSendCryptoKeyMaterial()
    val key = cryptoGenerator.makeSendCryptoKey(material)
    val frame = EncryptedFileFrame.encrypt(key, content)
    val encryptedFileName = accounts.encrypt(key, fileName)
    val reservation = httpClient
        .post("$baseUrl/api/sends/file/v2") {
            bearerAuth(accessToken)
            setJsonBody(
                sendBody(account, material, key, type = SEND_TYPE_FILE, passwordHash = null) {
                    putJsonObject("file") {
                        put("fileName", encryptedFileName)
                    }
                    put("fileLength", frame.size)
                },
            )
        }
        .requireJson("Create a file Send")
        .jsonObject
    // The upload URL is relative to the API root: /sends/{id}/file/{fileId}.
    httpClient
        .submitFormWithBinaryData(
            url = "$baseUrl/api${reservation.string("url")}",
            formData = formData {
                append(
                    key = "data",
                    value = frame,
                    // Vaultwarden rejects a part whose file name differs from the Send's.
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$encryptedFileName\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    },
                )
            },
        ) {
            bearerAuth(accessToken)
        }
        .requireSuccess("Upload the Send file")
    val send = reservation.getValue("sendResponse").jsonObject
    return SeededSend(send.string("id"), send.string("accessId"), material, key, passwordHashB64 = null)
}

/** Bitwarden's Send password hash: PBKDF2-SHA256 salted with the Send key material. */
internal fun VaultwardenApi.sendPasswordHash(password: String, material: ByteArray): String = cryptoGenerator
    .pbkdf2(seed = password.encodeToByteArray(), salt = material, iterations = SEND_PASSWORD_ITERATIONS)
    .let(base64Service::encodeToString)

private fun VaultwardenApi.sendBody(
    account: TestAccount,
    material: ByteArray,
    key: ByteArray,
    type: Int,
    passwordHash: String?,
    content: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("type", type)
    put("key", accounts.encrypt(account.userKey, material))
    put("name", accounts.encrypt(key, "Keyguard E2E"))
    put("notes", JsonNull)
    content()
    put("maxAccessCount", JsonNull)
    put("expirationDate", JsonNull)
    put("deletionDate", (Clock.System.now() + 1.days).toString())
    put("disabled", false)
    put("hideEmail", false)
    put("password", passwordHash)
}

private const val SEND_TYPE_TEXT = 0
private const val SEND_TYPE_FILE = 1
private const val SEND_PASSWORD_ITERATIONS = 100_000
