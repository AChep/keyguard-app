package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val WEBAUTHN_PUBLIC_KEY_CREDENTIAL_TYPE = "public-key"

internal data class WebAuthnPublicKeyCredentialDescriptor(
    val type: String,
    val credentialId: String,
) {
    fun matches(
        credential: DSecret.Login.Fido2Credentials,
    ): Boolean = type == credential.keyType &&
            credentialId == credential.credentialId

    fun toPasskeyTargetAllowedCredential(): PasskeyTarget.AllowedCredential =
        PasskeyTarget.AllowedCredential(
            type = type,
            credentialId = credentialId,
        )
}

internal data class WebAuthnAllowedCredentialDescriptors(
    val isAllowCredentialsSupplied: Boolean,
    val descriptors: List<WebAuthnPublicKeyCredentialDescriptor>,
) {
    fun toPasskeyTargetAllowedCredentials(): List<PasskeyTarget.AllowedCredential>? {
        if (!isAllowCredentialsSupplied) {
            return null
        }
        return descriptors
            .map(WebAuthnPublicKeyCredentialDescriptor::toPasskeyTargetAllowedCredential)
    }

    fun allows(
        credential: DSecret.Login.Fido2Credentials,
    ): Boolean {
        if (!isAllowCredentialsSupplied) {
            return credential.discoverable
        }
        return descriptors.any { descriptor ->
            descriptor.matches(credential)
        }
    }
}

/**
 * Parses the WebAuthn `allowCredentials` descriptor list and keeps only
 * descriptor types this authenticator supports.
 *
 * WebAuthn Level 3 says `PublicKeyCredentialDescriptor.type` identifies the
 * credential type, clients must ignore descriptors with an unknown type, and if
 * every supplied descriptor is ignored due to unknown type, the request must
 * error because an empty `allowCredentials` value has different semantics.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#dictdef-publickeycredentialdescriptor
 */
internal fun parseWebAuthnAllowedCredentialDescriptors(
    requestJson: String,
    json: Json,
    decodeCredentialId: (String) -> ByteArray = PasskeyBase64::decode,
): WebAuthnAllowedCredentialDescriptors {
    val body = json.parseToJsonElement(requestJson) as? JsonObject
        ?: return WebAuthnAllowedCredentialDescriptors(
            isAllowCredentialsSupplied = false,
            descriptors = emptyList(),
        )
    val allowCredentials = body["allowCredentials"] as? JsonArray
        ?: return WebAuthnAllowedCredentialDescriptors(
            isAllowCredentialsSupplied = false,
            descriptors = emptyList(),
        )
    if (allowCredentials.isEmpty()) {
        return WebAuthnAllowedCredentialDescriptors(
            isAllowCredentialsSupplied = false,
            descriptors = emptyList(),
        )
    }

    val descriptors = allowCredentials
        .mapNotNull { element ->
            val descriptor = element as? JsonObject
                ?: throw IllegalArgumentException(
                    "WebAuthn credential descriptor must be a JSON object.",
                )
            val type = descriptor.requireString("type")
            val idBase64 = descriptor.requireString("id")
            val credentialId = decodeAllowedCredentialId(
                idBase64 = idBase64,
                decodeCredentialId = decodeCredentialId,
            )
            if (type != WEBAUTHN_PUBLIC_KEY_CREDENTIAL_TYPE) {
                return@mapNotNull null
            }

            WebAuthnPublicKeyCredentialDescriptor(
                type = type,
                credentialId = credentialId,
            )
        }
    if (descriptors.isEmpty()) {
        throw WebAuthnNotAllowedException(
            "All allowCredentials credential descriptor types are unsupported.",
        )
    }
    return WebAuthnAllowedCredentialDescriptors(
        isAllowCredentialsSupplied = true,
        descriptors = descriptors,
    )
}

private fun decodeAllowedCredentialId(
    idBase64: String,
    decodeCredentialId: (String) -> ByteArray,
): String {
    return try {
        val data = decodeCredentialId(idBase64)
        PasskeyCredentialId.decode(data)
    } catch (_: IllegalArgumentException) {
        // WebAuthn parseRequestOptionsFromJSON says JSON buffer-source
        // parsing issues MUST throw "EncodingError" before get() proceeds.
        // Spec:
        // - https://www.w3.org/TR/webauthn-3/#sctn-parseRequestOptionsFromJSON
        throw WebAuthnEncodingException("Malformed allowCredentials credential id.")
    }
}

private fun JsonObject.requireString(
    key: String,
): String {
    val value = this[key] as? JsonPrimitive
    require(value?.isString == true) {
        "WebAuthn credential descriptor `$key` must be a string."
    }
    return requireNotNull(value.contentOrNull) {
        "WebAuthn credential descriptor `$key` must be a string."
    }
}
