package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the PRF inputs of a registration, or returns `null` if the
 * relying party did not request the extension.
 */
internal fun CreatePasskey.toPrfRegistrationInputOrNull(): WebAuthnPrfRegistrationInput? {
    val prf = extensions?.prf.toPrfObjectOrNull()
        ?: return null
    // Only its presence matters: even an empty one fails.
    if ((prf["evalByCredential"] ?: JsonNull) != JsonNull) {
        throw WebAuthnNotSupportedException("A registration does not support the PRF evalByCredential input.")
    }
    return WebAuthnPrfRegistrationInput(
        eval = prf["eval"].toPrfObjectOrNull()?.toPrfValues(),
    )
}

/**
 * Fails a registration whose PRF inputs are malformed, or carry
 * `evalByCredential`, even an empty one, before anything is created.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#prf-extension
 */
fun CreatePasskey.requireWebAuthnPrfRegistrationInputs() {
    toPrfRegistrationInputOrNull()
}

/**
 * Parses the PRF inputs of WebAuthn assertion options, or returns
 * `null` if the relying party did not request the extension. The keys of
 * `evalByCredential` must name credentials of the [allowedCredentials].
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#prf-extension
 */
fun parseWebAuthnPrfAssertionInputs(
    body: JsonObject?,
    allowedCredentials: WebAuthnAllowedCredentialDescriptors,
): WebAuthnPrfAssertionInputs? {
    val extensions = body?.get("extensions") as? JsonObject
    val prf = extensions?.get("prf").toPrfObjectOrNull()
        ?: return null
    // Decoding the buffers is part of parsing the options, so a malformed
    // one fails before the extension processing checks the keys.
    val eval = prf["eval"].toPrfObjectOrNull()?.toPrfValues()
    val evalByCredential = prf["evalByCredential"].toPrfObjectOrNull()
        .orEmpty()
        .mapValues { (_, values) ->
            values.toPrfObjectOrNull()?.toPrfValues()
                ?: throw malformedPrfInputError()
        }
    return WebAuthnPrfAssertionInputs(
        eval = eval,
        evalByCredential = evalByCredential.keyedByAllowedCredential(allowedCredentials),
    )
}

/**
 * Keys the inputs by the base64url encoding of the credential ID they name,
 * after checking that the ID is one of the [allowedCredentials].
 */
private fun Map<String, WebAuthnPrfValues>.keyedByAllowedCredential(
    allowedCredentials: WebAuthnAllowedCredentialDescriptors,
): Map<String, WebAuthnPrfValues> {
    if (isEmpty()) return emptyMap()
    if (!allowedCredentials.isAllowCredentialsSupplied) {
        throw WebAuthnNotSupportedException("The PRF evalByCredential input requires allowCredentials.")
    }
    val allowedCredentialIds = allowedCredentials.descriptors
        .mapNotNull { PasskeyCredentialId.encodeOrNull(it.credentialId) }
        .mapTo(mutableSetOf(), PasskeyBase64::encodeToString)
    return mapKeys { (credentialId, _) ->
        credentialId
            .takeIf { it.isNotEmpty() }
            ?.let(::decodeCredentialIdOrNull)
            ?.let(PasskeyBase64::encodeToString)
            ?.takeIf { it in allowedCredentialIds }
            ?: throw WebAuthnSyntaxException("The PRF evalByCredential input names a credential that is not allowed.")
    }
}

private fun decodeCredentialIdOrNull(
    value: String,
): ByteArray? = try {
    PasskeyBase64.decode(value)
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.toPrfValues(): WebAuthnPrfValues {
    val first = this["first"].toPrfStringOrNull()
        ?: throw malformedPrfInputError()
    val second = this["second"].toPrfStringOrNull()
    return decodePrfValues(first, second)
}

/**
 * A member that is absent or a JSON null was not given; a member
 * of the wrong type is malformed, not ignored.
 */
private fun JsonElement?.toPrfObjectOrNull(): JsonObject? = when (this) {
    null, JsonNull -> null
    is JsonObject -> this
    else -> throw malformedPrfInputError()
}

/** As [toPrfObjectOrNull], for a string member. */
private fun JsonElement?.toPrfStringOrNull(): String? = when {
    this == null || this == JsonNull -> null
    this is JsonPrimitive && isString -> content
    else -> throw malformedPrfInputError()
}

private fun decodePrfValues(
    first: String,
    second: String?,
) = WebAuthnPrfValues(
    first = decodePrfBuffer(first),
    second = second?.let(::decodePrfBuffer),
)

private fun decodePrfBuffer(
    value: String,
): ByteArray = try {
    PasskeyBase64.decode(value)
} catch (_: IllegalArgumentException) {
    // JSON buffer-source parsing issues are an "EncodingError".
    // Spec:
    // - https://www.w3.org/TR/webauthn-3/#sctn-parseRequestOptionsFromJSON
    throw malformedPrfInputError()
}

private fun malformedPrfInputError() = WebAuthnEncodingException("Malformed PRF extension input.")
