package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.ui.icons.AccentColors

data class DProfile(
    val accountId: String,
    val profileId: String,
    val keyBase64: String,
    val privateKeyBase64: String,
    val accountHost: String,
    val email: String,
    val emailVerified: Boolean?,
    val accentColor: AccentColors,
    val name: String,
    val description: String,
    val premium: Boolean?,
    /**
     * `true` if the account should be hidden from the main screens and
     * not used during the autofill process, `false` otherwise.
     */
    val hidden: Boolean,
    val securityStamp: String?,
    val twoFactorEnabled: Boolean?,
    val masterPasswordHint: String?,
    val masterPasswordHintEnabled: Boolean?,
    val unofficialServer: Boolean,
    val serverVersion: String?,
) : HasAccountId, Comparable<DProfile> {
    private val comparator = compareBy<DProfile>(
        { name },
        { email },
    )

    override fun compareTo(other: DProfile): Int = comparator.compare(this, other)

    override fun accountId(): String = accountId
}

val DProfile.displayName: String
    get() = name.takeIf { it.isNotBlank() }
        ?: email
