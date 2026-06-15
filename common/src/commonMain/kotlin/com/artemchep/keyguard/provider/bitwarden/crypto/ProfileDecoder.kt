package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileEntity

fun BitwardenProfile.Companion.encrypted(
    accountId: String,
    entity: ProfileEntity,
    unofficialServer: Boolean,
) = kotlin.run {
    BitwardenProfile(
        accountId = accountId,
        profileId = entity.id,
        keyBase64 = entity.key,
        privateKeyBase64 = entity.privateKey,
        avatarColor = entity.avatarColor,
        culture = entity.culture,
        name = entity.name.orEmpty(),
        email = entity.email,
        emailVerified = entity.emailVerified,
        premium = entity.premium,
        securityStamp = entity.securityStamp,
        twoFactorEnabled = entity.twoFactorEnabled,
        masterPasswordHint = entity.masterPasswordHint,
        unofficialServer = unofficialServer,
    )
}
