package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey

fun requireNoExcludedPasskeyCredential(
    data: CreatePasskey,
    rpId: String,
    credentials: List<WebAuthnCredential>,
) {
    val excludedCredential = findExcludedPasskeyCredentialOrNull(
        data = data,
        rpId = rpId,
        credentials = credentials,
    ) ?: return

    throw WebAuthnInvalidStateException(
        "A matching excluded passkey already exists for `${excludedCredential.rpId}`.",
    )
}

fun findExcludedPasskeyCredentialOrNull(
    data: CreatePasskey,
    rpId: String,
    credentials: List<WebAuthnCredential>,
): WebAuthnCredential? {
    val excludedCredentialIds = decodeExcludedCredentialIds(data)
    if (excludedCredentialIds.isEmpty()) {
        return null
    }

    return credentials
        .asSequence()
        .firstOrNull { credential ->
            // WebAuthn authenticatorMakeCredential rejects with
            // InvalidStateError when, after user consent to create, an
            // excludeCredentials descriptor matches a credential source by id,
            // type, and RP ID. Before consent, the client must avoid revealing
            // whether a matching excluded credential exists.
            // Spec:
            // - https://www.w3.org/TR/webauthn-3/#sctn-op-make-cred
            // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
            credential.keyType == PUBLIC_KEY_CREDENTIAL_TYPE &&
                credential.rpId == rpId &&
                credential.credentialId in excludedCredentialIds
        }
}

fun decodeExcludedCredentialIds(
    data: CreatePasskey,
): Set<String> = data.excludeCredentials
    .asSequence()
    .mapNotNull { descriptor ->
        val credentialIdBytes = decodeExcludedCredentialIdBase64(
            idBase64 = descriptor.idBase64,
        )
        if (descriptor.type != PUBLIC_KEY_CREDENTIAL_TYPE) {
            return@mapNotNull null
        }
        PasskeyCredentialId.decode(credentialIdBytes)
    }
    .toSet()

private fun decodeExcludedCredentialIdBase64(
    idBase64: String,
): ByteArray {
    return try {
        PasskeyBase64.decode(idBase64)
    } catch (e: IllegalArgumentException) {
        // WebAuthn parseCreationOptionsFromJSON says JSON buffer-source
        // parsing issues MUST throw "EncodingError"; authenticatorMakeCredential
        // first requires inputs to be "syntactically well-formed".
        // Spec:
        // - https://www.w3.org/TR/webauthn-3/#sctn-parseCreationOptionsFromJSON
        // - https://www.w3.org/TR/webauthn-3/#sctn-op-make-cred
        throw WebAuthnEncodingException(
            message = "Malformed excludeCredentials credential id.",
            cause = e,
        )
    }
}
