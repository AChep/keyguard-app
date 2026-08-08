package com.artemchep.keyguard.common.service.crypto

/**
 * Platform-neutral identity of an OpenPGP provider operation. Transport
 * layers map their own action identifiers (and legacy synonyms) onto
 * these kinds before invoking the shared selection policy.
 */
internal enum class GpgOpenPgpOperationKind {
    CHECK_PERMISSION,
    GET_SIGN_KEY_ID,
    GET_KEY_IDS,
    GET_KEY,
    CLEAR_SIGN,
    DETACHED_SIGN,
    ENCRYPT,
    SIGN_AND_ENCRYPT,
    DECRYPT_VERIFY,
    DECRYPT_METADATA,
    AUTOCRYPT_STATUS,
    ;

    /** The operation consumes the caller's payload input stream. */
    val consumesInputStream: Boolean
        get() = when (this) {
            CLEAR_SIGN,
            DETACHED_SIGN,
            ENCRYPT,
            SIGN_AND_ENCRYPT,
            DECRYPT_VERIFY,
            DECRYPT_METADATA,
            -> true

            else -> false
        }

    /** The user may approve more than one key for the operation. */
    val allowsMultipleKeys: Boolean
        get() = when (this) {
            GET_KEY_IDS,
            AUTOCRYPT_STATUS,
            ENCRYPT,
            SIGN_AND_ENCRYPT,
            DECRYPT_VERIFY,
            DECRYPT_METADATA,
            -> true

            else -> false
        }

    /**
     * The operation stays meaningful with no keys selected: decryption
     * cannot know the addressed key before trying it.
     */
    val allowsEmptyKeySelection: Boolean
        get() = when (this) {
            DECRYPT_VERIFY,
            DECRYPT_METADATA,
            -> true

            else -> false
        }

    /** The operation uses private key material, so it needs an authenticated session. */
    val requiresPrivateKeyAuthorization: Boolean
        get() = when (this) {
            CLEAR_SIGN,
            DETACHED_SIGN,
            SIGN_AND_ENCRYPT,
            -> true

            else -> false
        }

    /** The operation resolves recipients from requested user IDs and key IDs. */
    val usesRecipientLookup: Boolean
        get() = when (this) {
            AUTOCRYPT_STATUS,
            GET_KEY_IDS,
            ENCRYPT,
            SIGN_AND_ENCRYPT,
            -> true

            else -> false
        }
}
