@file:Suppress("MagicNumber") // WebAuthn wire constants and authenticator-data offsets.

package com.artemchep.keyguard.feature.fido2

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.fido2.FIDO2_MAX_CREDENTIAL_LENGTH
import com.artemchep.keyguard.util.fido2.FIDO2_RP_ID
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val encoding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

internal fun Fido2Operation.webAuthnRequest(): String =
    buildJsonObject {
            put("challenge", encoding.encode(challenge))
            put("timeout", 60_000)
            when (val operation = this@webAuthnRequest) {
                is Fido2Operation.Register -> {
                    putJsonObject("rp") {
                        put("id", FIDO2_RP_ID)
                        put("name", "Keyguard")
                    }
                    putJsonObject("user") {
                        put("id", encoding.encode(operation.userId))
                        put("name", "Keyguard")
                        put("displayName", "Keyguard")
                    }
                    putJsonArray("pubKeyCredParams") {
                        addJsonObject {
                            put("type", "public-key")
                            put("alg", -7)
                        }
                    }
                    putJsonObject("authenticatorSelection") {
                        put("authenticatorAttachment", "cross-platform")
                        put("residentKey", "discouraged")
                        put("userVerification", "required")
                    }
                    put("attestation", "none")
                    putJsonObject("extensions") { putJsonObject("prf") {} }
                }
                is Fido2Operation.Derive -> {
                    put("rpId", FIDO2_RP_ID)
                    put("userVerification", "required")
                    putJsonArray("allowCredentials") {
                        addJsonObject {
                            put("type", "public-key")
                            put("id", encoding.encode(operation.credentialId))
                            putJsonArray("transports") { add("usb") }
                        }
                    }
                    putJsonObject("extensions") {
                        putJsonObject("prf") {
                            putJsonObject("eval") { put("first", encoding.encode(operation.salt)) }
                        }
                    }
                }
            }
        }
        .toString()

@Suppress("ThrowsCount") // Distinguish unsupported PRF from malformed device responses.
internal fun Fido2Operation.webAuthnResponse(response: String): ByteArray {
    val root = Json.parseToJsonElement(response).jsonObject
    val credentialId = encoding.decode(root.getValue("rawId").jsonPrimitive.content)
    require(credentialId.size in 1..FIDO2_MAX_CREDENTIAL_LENGTH)
    val prf =
        root["clientExtensionResults"]?.jsonObject?.get("prf")?.jsonObject
            ?: throw Fido2Exception(Fido2Failure.UNSUPPORTED)
    return when (this) {
        is Fido2Operation.Register -> {
            if (prf["enabled"]?.jsonPrimitive?.booleanOrNull != true)
                throw Fido2Exception(Fido2Failure.UNSUPPORTED)
            credentialId
        }
        is Fido2Operation.Derive -> {
            require(credentialId.contentEquals(this.credentialId))
            val authData =
                encoding.decode(
                    root
                        .getValue("response")
                        .jsonObject
                        .getValue("authenticatorData")
                        .jsonPrimitive
                        .content
                )
            require(authData.size >= 37 && authData[32].toInt() and 5 == 5)
            require(
                authData
                    .copyOfRange(0, 32)
                    .contentEquals(NativeCrypto.primitives.sha256(FIDO2_RP_ID.encodeToByteArray()))
            )
            val value =
                prf["results"]?.jsonObject?.get("first")?.jsonPrimitive?.content
                    ?: throw Fido2Exception(Fido2Failure.UNSUPPORTED)
            encoding.decode(value).also { bytes ->
                if (bytes.size != 32) {
                    bytes.fill(0)
                    throw Fido2Exception(Fido2Failure.PROTOCOL)
                }
            }
        }
    }
}
