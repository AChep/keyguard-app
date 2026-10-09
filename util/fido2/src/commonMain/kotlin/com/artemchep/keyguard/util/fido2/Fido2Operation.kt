package com.artemchep.keyguard.util.fido2

/** Stable relying party for local vault unlock. Changing it invalidates enrolled credentials. */
const val FIDO2_RP_ID = "keyguard.dev"
const val FIDO2_SECRET_LENGTH = 32
const val FIDO2_INPUT_LENGTH = 32
const val FIDO2_MAX_CREDENTIAL_LENGTH = 1024
internal const val FIDO2_MAX_PIN_LENGTH = 63
internal const val FIDO2_ABI_VERSION = 2
internal const val FIDO2_MAX_RESPONSE = 131072

/** Fresh authentication challenge; [Derive.salt] remains fixed for an enrollment. */
sealed class Fido2Operation(val challenge: ByteArray) {
    class Register(val userId: ByteArray, challenge: ByteArray) : Fido2Operation(challenge) {
        init {
            require(challenge.size == FIDO2_INPUT_LENGTH)
            require(userId.size == FIDO2_INPUT_LENGTH)
        }
    }

    class Derive(val credentialId: ByteArray, val salt: ByteArray, challenge: ByteArray) :
        Fido2Operation(challenge) {
        init {
            require(challenge.size == FIDO2_INPUT_LENGTH)
            require(credentialId.size in 1..FIDO2_MAX_CREDENTIAL_LENGTH)
            require(salt.size == FIDO2_INPUT_LENGTH)
        }
    }

    class Assert(val request: Fido2AssertionRequest) : Fido2Operation(request.challenge)
}
