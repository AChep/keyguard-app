package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import org.kodein.di.DirectDI

/**
 * @author Artem Chepurnyi
 */
class PasskeyTargetCheckImpl : PasskeyTargetCheck {
    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(directDI: DirectDI) : this()

    override fun invoke(
        credential: DSecret.Login.Fido2Credentials,
        target: PasskeyTarget,
    ): IO<Boolean> = ioEffect {
        // Check that the credential is mentioned in the request.
        //
        // If relying party does not provide us a list of credentials to choose
        // from, then the credential must be marked as 'discoverable'.
        //
        // See:
        // https://www.w3.org/TR/webauthn-2/#discoverable-credential
        if (target.allowedCredentials != null) {
            val matchesAllowedCredentials = target.allowedCredentials
                .any {
                    // WebAuthn credential descriptors identify a credential by
                    // both id and type. Matching only by id can surface a
                    // credential that must later fail authenticatorGetAssertion.
                    // Spec: https://www.w3.org/TR/webauthn-3/#dictdef-publickeycredentialdescriptor
                    it.credentialId == credential.credentialId &&
                            it.type == credential.keyType
                }
            if (!matchesAllowedCredentials) {
                return@ioEffect false
            }
        } else if (!credential.discoverable) {
            return@ioEffect false
        }

        val passkeyRpId = credential.rpId
        val requestRpId = target.rpId
        validatePasskeyRpId(
            passkeyRpId = passkeyRpId,
            requestRpId = requestRpId,
        )
    }

    // See:
    // WebAuthn puts the selected credential's RP ID hash in authenticator data
    // and signs that data during authenticatorGetAssertion, so the stored RP ID
    // must match the resolved request RP ID. A parent-domain RP ID may be valid
    // for an origin, but it must be explicit in the request; missing `rpId` is
    // resolved before this check.
    // https://www.w3.org/TR/webauthn-3/#sctn-authenticator-data
    // https://www.w3.org/TR/webauthn-3/#rp-id
    private suspend fun validatePasskeyRpId(
        passkeyRpId: String?,
        requestRpId: String?,
    ): Boolean {
        return passkeyRpId == requestRpId
    }
}

fun isSubdomain(
    domain: String,
    request: String,
): Boolean {
    val domainParts = domain.split('.')
    val requestParts = request.split('.')

    val offset = requestParts.size - domainParts.size
    if (offset < 0) {
        return false
    }

    return domainParts.withIndex().all { (passkeyRpIdIndex, passkeyRpIdPart) ->
        val requestRpIdIndex = passkeyRpIdIndex + offset
        val requestRpIdPart = requestParts[requestRpIdIndex]
        requestRpIdPart == passkeyRpIdPart
    }
}
