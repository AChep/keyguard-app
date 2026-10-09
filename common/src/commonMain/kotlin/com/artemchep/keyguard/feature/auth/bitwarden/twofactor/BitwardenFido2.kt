@file:Suppress("MagicNumber") // WebAuthn authenticator-data flags and bounds.

package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.fido2.Fido2AssertionRequest
import com.artemchep.keyguard.util.fido2.Fido2AssertionResult
import com.artemchep.keyguard.util.fido2.Fido2UserVerification
import com.artemchep.keyguard.util.fido2.parseFido2AssertionRequest
import com.artemchep.keyguard.util.webauthn.canonicalizeWebAuthnRpId
import com.artemchep.keyguard.util.webauthn.isValidCanonicalWebAuthnRpId
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

internal fun bitwardenFido2Request(options: JsonElement, webVaultUrl: String): Fido2AssertionRequest {
    val vault = Url(webVaultUrl)
    val host = canonicalizeWebAuthnRpId(vault.host)
    require(vault.user == null && vault.password == null && isValidCanonicalWebAuthnRpId(host))
    require(vault.protocol.name == "https" || vault.protocol.name == "http" && host == "localhost")
    val port = if (vault.port == vault.protocol.defaultPort) "" else ":${vault.port}"
    val origin = "${vault.protocol.name}://$host$port"
    val root = options.jsonObject.toMutableMap()
    val rpId = root["rpId"]?.jsonPrimitive?.content ?: host
    // Bitwarden and Vaultwarden scope 2FA to the configured web-vault host.
    // Never let a custom server request a credential for another environment.
    require(rpId == host)
    root["rpId"] = JsonPrimitive(rpId)
    val request = parseFido2AssertionRequest(JsonObject(root).toString(), origin)
    request.appId?.let { appId ->
        require(Url(appId) == Url(webVaultUrl.trimEnd('/') + "/app-id.json"))
    }
    return request
}

internal fun bitwardenFido2Token(request: Fido2AssertionRequest, result: Fido2AssertionResult): String {
    require(request.credentials.any { it.id.contentEquals(result.credentialId) })
    require(result.authenticatorData.size in 37..65536 && result.signature.size in 1..4096)
    require((result.userHandle?.size ?: 0) <= 64)
    val rp = if (result.appIdUsed) requireNotNull(request.appId) else request.rpId
    val expectedHash = NativeCrypto.primitives.sha256(rp.encodeToByteArray())
    require(result.authenticatorData.copyOfRange(0, 32).contentEquals(expectedHash))
    val flags = if (request.userVerification == Fido2UserVerification.REQUIRED) 5 else 1
    require(result.authenticatorData[32].toInt() and flags == flags)
    require(result.clientDataJson.size in 1..8192)
    val clientData = Json.parseToJsonElement(result.clientDataJson.decodeToString()).jsonObject
    require(clientData.getValue("type").jsonPrimitive.content == "webauthn.get")
    require(clientData.getValue("origin").jsonPrimitive.content == request.origin)
    require(base64.decode(clientData.getValue("challenge").jsonPrimitive.content).contentEquals(request.challenge))
    require(clientData["crossOrigin"] == null || clientData["crossOrigin"] == JsonPrimitive(false))
    return buildJsonObject {
        put("id", base64.encode(result.credentialId))
        put("rawId", base64.encode(result.credentialId))
        put("type", "public-key")
        putJsonObject("extensions") { if (request.appId != null) put("appid", result.appIdUsed) }
        putJsonObject("response") {
            put("authenticatorData", base64.encode(result.authenticatorData))
            put("clientDataJson", base64.encode(result.clientDataJson))
            put("signature", base64.encode(result.signature))
            result.userHandle?.let { put("userHandle", base64.encode(it)) }
        }
    }.toString()
}

internal fun bitwardenFido2BrowserResult(token: String): Fido2AssertionResult {
    require(token.length <= 131072)
    val root = Json.parseToJsonElement(token).jsonObject
    require(root.getValue("type").jsonPrimitive.content == "public-key")
    val response = root.getValue("response").jsonObject
    return Fido2AssertionResult(
        credentialId = base64.decode(root.getValue("rawId").jsonPrimitive.content),
        authenticatorData = base64.decode(response.getValue("authenticatorData").jsonPrimitive.content),
        signature = base64.decode(response.getValue("signature").jsonPrimitive.content),
        userHandle = response["userHandle"]?.jsonPrimitive?.contentOrNull?.let(base64::decode),
        appIdUsed = root["extensions"]?.jsonObject?.get("appid")?.jsonPrimitive?.booleanOrNull == true,
        clientDataJson = base64.decode(
            (response["clientDataJson"] ?: response.getValue("clientDataJSON")).jsonPrimitive.content,
        ),
    )
}
