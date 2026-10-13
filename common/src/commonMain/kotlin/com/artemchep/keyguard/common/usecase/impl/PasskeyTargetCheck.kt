package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.util.webauthn.canAnswer

/**
 * @author Artem Chepurnyi
 */
class PasskeyTargetCheckImpl : PasskeyTargetCheck {
    // Every picker offers exactly the passkeys the authenticator can then sign with.
    override fun invoke(
        credential: DSecret.Login.Fido2Credentials,
        target: PasskeyTarget,
    ): IO<Boolean> = ioEffect {
        credential.toWebAuthnCredential().canAnswer(
            rpId = target.rpId,
            allowed = target.allowed,
        )
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
