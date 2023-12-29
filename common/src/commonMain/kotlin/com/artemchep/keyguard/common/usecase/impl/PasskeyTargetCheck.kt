package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PasskeyTargetCheckImpl(
    private val tldService: TldService,
) : PasskeyTargetCheck {
    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
    )

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
                .any { it.credentialId == credential.credentialId }
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
    // https://webauthn-doc.spomky-labs.com/prerequisites/the-relying-party#how-to-determine-the-relying-party-id
    private suspend fun validatePasskeyRpId(
        passkeyRpId: String?,
        requestRpId: String?,
    ): Boolean {
        // Fast path:
        // if the relying party is the exact match or if
        // relying party is not specified on both the passkey and the
        // request.
        if (passkeyRpId == requestRpId) {
            return true
        }
        if (passkeyRpId == null || requestRpId == null) {
            return false
        }
        // Relaying party ID must point for a
        // valid domain.
        val canMatchSubdomain = true
        if (canMatchSubdomain) {
            return isSubdomain(
                domain = passkeyRpId,
                request = requestRpId,
            )
        }
        return false
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
