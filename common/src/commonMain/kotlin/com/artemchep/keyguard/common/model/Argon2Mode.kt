package com.artemchep.keyguard.common.model

/**
 * The different Argon2 modes that differ regarding side-channel-and-memory-tradeoffs.
 * Please refer to the documentation of the Argon2 project for details.
 */
enum class Argon2Mode(val identifier: Int) {

    /**
     * Argon2d chooses memory depending on the password and salt.
     * Not suitable for environments with potential side-channel attacks.
     */
    ARGON2_D(0),

    /**
     * Argon2i chooses memory independent of the password and salt
     * reducing the risk from side-channels. However, the
     * memory trade-off is weaker.
     */
    ARGON2_I(1),

    /**
     * Argon2id combines the Argon2d and Argon2i providing
     * a reasonable trade-off between memory dependence and side-channels.
     */
    ARGON2_ID(2),
}
