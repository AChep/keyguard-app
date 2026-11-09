@file:Suppress("MagicNumber") // CTAP makeCredential parameter labels and bounds.

package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfRegistrationInput
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.isValidCanonicalWebAuthnRpId

internal fun registrationRequest(
    parameters: Map<*, *>,
    prfSession: HybridPrfSession,
): HybridRegistrationRequest {
    val hash = parameters[1L] as? ByteArray ?: error("Missing client data hash")
    require(hash.size == 32)
    val rp = parameters[2L] as? Map<*, *> ?: error("Missing RP")
    val rpId = rp["id"] as? String ?: error("Missing RP ID")
    require(isValidCanonicalWebAuthnRpId(rpId))
    val user = parameters[3L] as? Map<*, *> ?: error("Missing user")
    val id = user["id"] as? ByteArray ?: error("Missing user handle")
    require(id.size in 1..64)
    val allowedAlgorithms = registrationAlgorithms(parameters[4L])
    val options = registrationOptions(parameters)
    val excludedIds = registrationExclusions(parameters[5L])
    val extensions = parameters[6L]?.let { it as? Map<*, *> ?: error("Invalid extensions") }
    require(extensions?.get("hmac-secret") == null || extensions["hmac-secret"] is Boolean)
    val hmac = extensions?.get("hmac-secret") == true
    if (extensions?.containsKey("hmac-secret-mc") == true && !hmac) {
        throw HybridCtapException(HybridCtap.MISSING_PARAMETER)
    }
    val prf = prfSession.open(extensions?.get("hmac-secret-mc"))
    var transferred = false
    return try {
        HybridRegistrationRequest(
            options =
                WebAuthnRegistrationRequest(
                    rpId = rpId,
                    rpName = rp["name"] as? String,
                    userHandle = PasskeyBase64.encodeToString(id),
                    userName = user["name"] as? String,
                    userDisplayName = user["displayName"] as? String,
                    pubKeyCredParams = allowedAlgorithms,
                    discoverable = options?.get("rk") == true,
                    userVerification = "required",
                    excludedCredentialIds = excludedIds,
                    prf = if (hmac) WebAuthnPrfRegistrationInput(prf?.values, salted = true) else null,
                ),
            prf = prf,
        ).also { transferred = true }
    } finally {
        if (!transferred) prf?.clear()
    }
}

private fun registrationAlgorithms(input: Any?): List<CreatePasskeyPubKeyCredParams> {
    val algorithms = input as? List<*> ?: error("Missing algorithms")
    require(algorithms.size in 1..64)
    val allowedAlgorithms =
        algorithms.map { value ->
            val algorithm = value as? Map<*, *> ?: error("Invalid algorithm")
            CreatePasskeyPubKeyCredParams(
                type = algorithm["type"] as? String ?: error("Missing algorithm type"),
                alg = (algorithm["alg"] as? Long ?: error("Missing algorithm value")).toDouble(),
            )
        }
    if (allowedAlgorithms.none { it.type == "public-key" && it.alg == -7.0 }) {
        throw HybridCtapException(HybridCtap.UNSUPPORTED_ALGORITHM)
    }
    return allowedAlgorithms
}

private fun registrationExclusions(input: Any?): Set<String> =
    credentialDescriptors(input)
        .filter { it.type == "public-key" }
        .mapTo(mutableSetOf()) { it.credentialId }

private fun registrationOptions(parameters: Map<*, *>): Map<*, *>? {
    val options = parameters[7L]?.let { it as? Map<*, *> ?: error("Invalid options") }
    require(options?.values?.all { it is Boolean } != false)
    // CTAP 2.2 §6.1.2: creation always needs user presence.
    if (options?.get("up") == false) throw HybridCtapException(HybridCtap.INVALID_OPTION)
    if (parameters[8L] != null) throw HybridCtapException(HybridCtap.PIN_AUTH_INVALID)
    require(parameters[9L] == null || parameters[9L] in listOf(1L, 2L))
    require(parameters[10L] == null)
    return options
}
