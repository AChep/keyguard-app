package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import kotlin.math.roundToInt

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

fun CreatePasskey.pubKeyCredParamsOrDefaults(): List<CreatePasskeyPubKeyCredParams> {
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

fun findPasskeyAlgorithmOrNull(
    data: CreatePasskey,
    supportedAlgorithms: Set<PasskeySignatureAlgorithm>,
): PasskeySignatureAlgorithm? {
    val pubKeyCredParams = data.pubKeyCredParamsOrDefaults()
    return pubKeyCredParams.firstNotNullOfOrNull { parameters ->
        supportedAlgorithms.firstOrNull { algorithm ->
            parameters.type == "public-key" &&
                parameters.alg.roundToInt() == algorithm.coseValue
        }
    }
}

fun requirePasskeyAlgorithm(
    data: CreatePasskey,
    supportedAlgorithms: Set<PasskeySignatureAlgorithm>,
): PasskeySignatureAlgorithm =
    findPasskeyAlgorithmOrNull(
        data = data,
        supportedAlgorithms = supportedAlgorithms,
    ) ?: throw WebAuthnNotSupportedException(
        // WebAuthn L3 create() throws NotSupportedError when no
        // pubKeyCredParams entry has type "public-key" or no listed
        // public-key algorithm is supported by the authenticator.
        // Spec:
        // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
        // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential-exceptions
        message = "None of the allowed public key parameters are supported by the app.",
    )
