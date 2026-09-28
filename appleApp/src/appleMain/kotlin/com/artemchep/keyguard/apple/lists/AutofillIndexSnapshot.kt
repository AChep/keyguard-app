package com.artemchep.keyguard.apple.lists

/** One successful database read. Null at the bridge means locked, not an empty vault. */
data class AutofillIndexSnapshot(
    val passwords: List<AutofillIdentitySnapshot>,
    val oneTimeCodes: List<AutofillIdentitySnapshot>,
    val passkeys: List<PasskeyIdentitySnapshot>,
    val skippedPasskeys: Int = 0,
)
