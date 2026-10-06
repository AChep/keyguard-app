package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyAttestation
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams

/** Authenticator inputs, after the provider has validated the RP and obtained consent. */
data class WebAuthnRegistrationRequest(
    val rpId: String,
    val userHandle: String,
    val pubKeyCredParams: List<CreatePasskeyPubKeyCredParams>,
    val discoverable: Boolean,
    val rpName: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val userVerification: String? = null,
    val attestation: CreatePasskeyAttestation? = null,
    val excludedCredentialIds: Set<String> = emptySet(),
)

internal fun CreatePasskey.toRegistrationRequest(rpId: String) = WebAuthnRegistrationRequest(
    rpId = rpId,
    rpName = rp.name,
    userHandle = user.id,
    userName = user.name,
    userDisplayName = user.displayName,
    pubKeyCredParams = pubKeyCredParamsOrDefaults(),
    discoverable = authenticatorSelection.requireResidentKey ||
        authenticatorSelection.residentKey == "required" ||
        authenticatorSelection.residentKey == "preferred",
    userVerification = authenticatorSelection.userVerification,
    attestation = attestation,
    excludedCredentialIds = decodeExcludedCredentialIds(this),
)
