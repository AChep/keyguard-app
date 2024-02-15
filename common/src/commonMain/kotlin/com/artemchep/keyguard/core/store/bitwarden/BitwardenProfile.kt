package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenProfile(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val profileId: String,
    // common
    val avatarColor: String? = null,
    val name: String,
    val keyBase64: String,
    val privateKeyBase64: String,
    val culture: String = "",
    val email: String,
    val emailVerified: Boolean,
    val premium: Boolean,
    // Keyguard-specific field
    val hidden: Boolean = false,
    val securityStamp: String = "",
    val twoFactorEnabled: Boolean,
    val masterPasswordHint: String?,
    val unofficialServer: Boolean = false,
) {
    companion object;
}
