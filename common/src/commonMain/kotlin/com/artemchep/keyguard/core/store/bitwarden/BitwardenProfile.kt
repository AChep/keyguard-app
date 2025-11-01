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
    val description: String? = null,
    val keyBase64: String,
    val privateKeyBase64: String,
    val culture: String = "",
    val email: String,
    /** `null` if the email can not be verified */
    val emailVerified: Boolean? = null,
    val premium: Boolean? = null,
    // Keyguard-specific field
    val hidden: Boolean = false,
    val securityStamp: String = "",
    val twoFactorEnabled: Boolean? = null,
    val masterPasswordHint: String?,
    val masterPasswordHintEnabled: Boolean? = true,
    val unofficialServer: Boolean = false,
    val serverVersion: String? = null,
) {
    companion object;
}
