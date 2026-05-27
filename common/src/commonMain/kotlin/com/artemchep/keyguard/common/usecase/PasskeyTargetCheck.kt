package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret

data class PasskeyTarget(
    val allowedCredentials: List<AllowedCredential>? = null,
    val rpId: String?,
) {
    data class AllowedCredential(
        val credentialId: String,
        /**
         * WebAuthn credential descriptor matching is based on both `id` and
         * `type`; unknown descriptor types must be ignored before this model is
         * created.
         *
         * Spec: https://www.w3.org/TR/webauthn-3/#dictdef-publickeycredentialdescriptor
         */
        val type: String = "public-key",
    )
}

interface PasskeyTargetCheck : (DSecret.Login.Fido2Credentials, PasskeyTarget) -> IO<Boolean>
