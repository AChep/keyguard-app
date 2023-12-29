package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret

data class PasskeyTarget(
    val allowedCredentials: List<AllowedCredential>? = null,
    val rpId: String?,
) {
    data class AllowedCredential(
        val credentialId: String,
    )
}

interface PasskeyTargetCheck : (DSecret.Login.Fido2Credentials, PasskeyTarget) -> IO<Boolean>
