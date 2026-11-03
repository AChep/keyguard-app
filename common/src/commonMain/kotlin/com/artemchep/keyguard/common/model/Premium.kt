package com.artemchep.keyguard.common.model

/**
 * Declared from the lowest to the highest tier.
 */
enum class Premium {
    /** Premium features are locked. */
    NONE,

    /** Unlocked by the build itself, without a purchase. */
    INCLUDED,

    /** Unlocked by a store purchase, a license key or a debug grant. */
    PURCHASED,
}

val Premium.isActive: Boolean get() = this != Premium.NONE
