package com.artemchep.keyguard.util.foundation.crypto

import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode

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

fun Argon2Mode.toNativeArgon2Mode(): NativeArgon2Mode = when (this) {
    Argon2Mode.ARGON2_D -> NativeArgon2Mode.ARGON2_D
    Argon2Mode.ARGON2_I -> NativeArgon2Mode.ARGON2_I
    Argon2Mode.ARGON2_ID -> NativeArgon2Mode.ARGON2_ID
}
