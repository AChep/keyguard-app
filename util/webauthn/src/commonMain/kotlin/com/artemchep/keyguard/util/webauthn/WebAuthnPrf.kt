package com.artemchep.keyguard.util.webauthn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The length of the random secret a credential evaluates the PRF with. */
const val WEBAUTHN_PRF_SECRET_BYTES = 32

/**
 * The longest PRF secret a stored credential may have. Far above any real
 * secret, which is 32 bytes, but keeps a crafted document from storing
 * megabytes in a synced vault.
 */
const val MAX_WEBAUTHN_PRF_SECRET_BYTES = 64 * 1024

// "WebAuthn PRF" followed by a zero byte.
private val PRF_SALT_LABEL = "WebAuthn PRF".encodeToByteArray() + 0

/**
 * The inputs or the outputs of the PRF extension.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#dictdef-authenticationextensionsprfvalues
 */
class WebAuthnPrfValues(
    val first: ByteArray,
    val second: ByteArray? = null,
) {
    fun clear() {
        first.fill(0)
        second?.fill(0)
    }
}

/** The PRF inputs of a registration that requested the extension. */
data class WebAuthnPrfRegistrationInput(
    val eval: WebAuthnPrfValues? = null,
    /** Whether [eval] holds the salts that the platform already derived from the inputs. */
    val salted: Boolean = false,
)

/**
 * The PRF inputs of an assertion. [evalByCredential] is keyed by the
 * base64url credential ID and takes precedence over [eval].
 */
data class WebAuthnPrfAssertionInputs(
    val eval: WebAuthnPrfValues? = null,
    val evalByCredential: Map<String, WebAuthnPrfValues> = emptyMap(),
) {
    fun valuesFor(credentialId: ByteArray): WebAuthnPrfValues? =
        evalByCredential[PasskeyBase64.encodeToString(credentialId)] ?: eval
}

/**
 * Evaluates the PRF of a credential the way a CTAP2 authenticator evaluates
 * `hmac-secret` with its `CredRandom`, after the client hashed the input.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#prf-extension
 */
internal fun webAuthnPrf(
    secret: ByteArray,
    input: ByteArray,
    hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
    sha256: (ByteArray) -> ByteArray,
): ByteArray {
    val salt = webAuthnPrfSalt(input, sha256)
    return try {
        hmacSha256(secret, salt)
    } finally {
        salt.fill(0)
    }
}

/** The salt that a client derives from a PRF input before it reaches the authenticator. */
internal fun webAuthnPrfSalt(
    input: ByteArray,
    sha256: (ByteArray) -> ByteArray,
): ByteArray = sha256(PRF_SALT_LABEL + input)

/** Evaluates the inputs, or the [salted] values that a platform already derived from them. */
internal fun WebAuthnPrfValues.evaluate(
    secret: ByteArray,
    salted: Boolean,
    hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray,
    sha256: (ByteArray) -> ByteArray,
): WebAuthnPrfValues {
    fun evaluate(value: ByteArray) = if (salted) {
        hmacSha256(secret, value)
    } else {
        webAuthnPrf(secret, value, hmacSha256, sha256)
    }
    return WebAuthnPrfValues(
        first = evaluate(first),
        second = second?.let(::evaluate),
    )
}

/**
 * The `prf` client extension output. [enabled] is reported by registrations
 * only; [results] are present when the credential evaluated the inputs.
 */
internal fun prfClientExtensionResults(
    enabled: Boolean?,
    results: WebAuthnPrfValues?,
): JsonObject = buildJsonObject {
    put(
        "prf",
        buildJsonObject {
            enabled?.let { put("enabled", it) }
            results?.let { values ->
                put(
                    "results",
                    buildJsonObject {
                        put("first", PasskeyBase64.encodeToString(values.first))
                        values.second?.let { put("second", PasskeyBase64.encodeToString(it)) }
                    },
                )
            }
        },
    )
}
