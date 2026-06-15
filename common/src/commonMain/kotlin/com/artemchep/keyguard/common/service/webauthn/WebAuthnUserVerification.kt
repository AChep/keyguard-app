package com.artemchep.keyguard.common.service.webauthn

private const val USER_VERIFICATION_REQUIRED = "required"
private const val USER_VERIFICATION_PREFERRED = "preferred"
private const val USER_VERIFICATION_DISCOURAGED = "discouraged"

// See:
// https://github.com/1Password/passkey-rs/blob/90c1c282649eceeb7cbe771bb8ce17b1b8463c60/passkey-client/src/lib.rs#L407
// https://github.com/kanidm/webauthn-rs/blame/25bc74ac0dc4280bf67ed3ff53fdf804dbb142c2/webauthn-rs-core/src/core.rs#L866
internal fun webAuthnUserVerifiedFlag(
    requirement: String?,
    userVerified: Boolean,
): Boolean = when (requirement ?: USER_VERIFICATION_PREFERRED) {
    USER_VERIFICATION_REQUIRED,
    USER_VERIFICATION_PREFERRED,
        -> userVerified

    USER_VERIFICATION_DISCOURAGED -> false
    else -> userVerified
}
