package com.artemchep.keyguard.common.model

enum class GpgKeyserverVerificationStatus(
    val code: Long,
) {
    /**
     * The key has not been checked against a keyserver yet, or the result of
     * the last check is no longer trusted.
     */
    UNKNOWN(0L),

    /**
     * The keyserver does not have this key (or the matching identity).
     */
    NOT_FOUND(1L),

    /**
     * The keyserver has the key, but the identity is not verified/published
     * (e.g. keys.openpgp.org returns the key by fingerprint but strips the
     * unverified e-mail user-ids).
     */
    FOUND_UNVERIFIED(2L),

    /**
     * The keyserver has the key and the identity is verified/published.
     */
    VERIFIED(3L),

    /**
     * The keyserver reports the key as revoked.
     */
    REVOKED(4L),
    ;

    companion object {
        fun of(code: Long): GpgKeyserverVerificationStatus =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
