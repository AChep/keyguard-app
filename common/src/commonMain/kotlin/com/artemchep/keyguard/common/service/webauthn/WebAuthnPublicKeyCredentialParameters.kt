package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskey
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyPubKeyCredParams

internal const val PUBLIC_KEY_CREDENTIAL_TYPE = "public-key"

private const val COSE_ALGORITHM_ES256 = -7
private const val COSE_ALGORITHM_RS256 = -257

private val DEFAULT_PUB_KEY_CRED_PARAMS = listOf(
    CreatePasskeyPubKeyCredParams(
        alg = COSE_ALGORITHM_ES256.toDouble(),
        type = PUBLIC_KEY_CREDENTIAL_TYPE,
    ),
    CreatePasskeyPubKeyCredParams(
        alg = COSE_ALGORITHM_RS256.toDouble(),
        type = PUBLIC_KEY_CREDENTIAL_TYPE,
    ),
)

internal fun CreatePasskey.pubKeyCredParamsOrDefaults(): List<CreatePasskeyPubKeyCredParams> {
    if (pubKeyCredParams.isNotEmpty()) {
        return pubKeyCredParams
    }

    // WebAuthn create() defaults an empty pkOptions.pubKeyCredParams list
    // to public-key ES256 and RS256 before checking whether the authenticator
    // supports any allowed algorithm.
    // Spec:
    // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
    return DEFAULT_PUB_KEY_CRED_PARAMS
}
