package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors

/**
 * A sign-in request a passkey is picked for: the resolved relying party and the
 * credentials it [allowed].
 */
data class PasskeyTarget(
    val rpId: String,
    val allowed: WebAuthnAllowedCredentialDescriptors = WebAuthnAllowedCredentialDescriptors.NONE,
)

interface PasskeyTargetCheck : (DSecret.Login.Fido2Credentials, PasskeyTarget) -> IO<Boolean>
