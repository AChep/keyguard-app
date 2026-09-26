package com.artemchep.keyguard.util.webauthn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun clientDataJsonBytes(
    json: Json,
    type: String,
    challenge: String,
    context: WebAuthnCallerContext,
): ByteArray {
    val clientData = buildJsonObject {
        put("type", type)
        put("challenge", challenge)
        put("origin", context.origin)
        context.androidPackageName?.let { put("androidPackageName", it) }
    }
    return json.encodeToString(clientData).encodeToByteArray()
}

internal fun credentialResponseJson(
    credentialId: ByteArray,
    response: JsonObject,
): JsonObject = buildJsonObject {
    put("id", credentialId)
    put("rawId", credentialId)
    put("type", "public-key")
    put("authenticatorAttachment", "cross-platform")
    put("response", response)
    put("clientExtensionResults", buildJsonObject { })
}

internal fun registrationResponseJson(
    clientData: ByteArray,
    authenticatorData: ByteArray,
    publicKeyAlgorithm: Int,
    publicKey: ByteArray,
    transports: List<String>,
): JsonObject = buildJsonObject {
    put("clientDataJSON", clientData)
    put("attestationObject", webAuthnNoneAttestationObject(authenticatorData))
    put("transports", buildJsonArray { transports.forEach { add(it) } })
    put("publicKeyAlgorithm", publicKeyAlgorithm)
    put("publicKey", publicKey)
    put("authenticatorData", authenticatorData)
}

internal fun assertionResponseJson(
    clientDataJson: String,
    authenticatorData: String,
    signature: String,
    userHandle: String?,
): JsonObject = buildJsonObject {
    put("clientDataJSON", clientDataJson)
    put("authenticatorData", authenticatorData)
    put("signature", signature)
    userHandle
        ?.takeIf { it.isNotEmpty() }
        ?.let { put("userHandle", it) }
}

private fun JsonObjectBuilder.put(key: String, data: ByteArray) {
    put(key, PasskeyBase64.encodeToString(data))
}
