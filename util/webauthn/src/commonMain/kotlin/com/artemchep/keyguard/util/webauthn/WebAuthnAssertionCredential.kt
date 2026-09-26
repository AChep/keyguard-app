package com.artemchep.keyguard.util.webauthn

import kotlinx.serialization.json.Json

fun requireCredentialRpIdMatchesRequest(
    credential: WebAuthnCredential,
    rpId: String,
) {
    // WebAuthn credentials are scoped to the RP ID chosen at registration, and
    // authenticatorGetAssertion must remove any credential source whose stored
    // RP ID is not exactly equal to the request RP ID before signing.
    // Spec:
    // - https://www.w3.org/TR/webauthn-3/#rp-id
    // - https://www.w3.org/TR/webauthn-3/#sctn-op-get-assertion
    if (credential.rpId != rpId) {
        throw WebAuthnNotAllowedException(
            "Credential RP ID '${credential.rpId}' does not match request RP ID '$rpId'.",
        )
    }
}

fun requireCredentialAllowedByRequestOptions(
    credential: WebAuthnCredential,
    requestJson: String,
    json: Json,
    decodeCredentialId: (String) -> ByteArray = PasskeyBase64::decode,
) {
    val allowCredentials = parseWebAuthnAllowedCredentialDescriptors(
        requestJson = requestJson,
        json = json,
        decodeCredentialId = decodeCredentialId,
    )
    requireCredentialAllowed(credential, allowCredentials)
}

internal fun requireCredentialAllowed(
    credential: WebAuthnCredential,
    allowCredentials: WebAuthnAllowedCredentialDescriptors,
) {
    // WebAuthn Level 3 authenticatorGetAssertion uses discoverable credentials
    // when no allowCredentialDescriptorList is supplied. If a list is supplied,
    // candidate credentials must match descriptor id and type; unknown types are
    // ignored by the shared parser, and an all-unknown list remains disallowed.
    // Spec:
    // - https://www.w3.org/TR/webauthn-3/#dom-publickeycredentialrequestoptions-allowcredentials
    // - https://www.w3.org/TR/webauthn-3/#sctn-op-get-assertion
    if (allowCredentials.allows(credential)) {
        return
    }

    val reason = if (allowCredentials.isAllowCredentialsSupplied) {
        "is not allowed by request allowCredentials."
    } else {
        "is not discoverable and the request did not supply allowCredentials."
    }
    throw WebAuthnNotAllowedException("Credential '${credential.credentialId}' $reason")
}
