package com.artemchep.keyguard.feature.send

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.requiresPremium

internal fun canUseAccountForSendType(
    account: DAccount,
    profile: DProfile,
    type: DSend.Type,
): Boolean = account.type.capabilities.supportsSends &&
        (!DSend.requiresPremium(type) || profile.premium == true)

internal fun hasEligibleAccountForSendType(
    accounts: List<DAccount>,
    profiles: List<DProfile>,
    type: DSend.Type,
): Boolean = profiles.any { profile ->
    val account = accounts.firstOrNull { it.id.id == profile.accountId }
        ?: return@any false
    canUseAccountForSendType(
        account = account,
        profile = profile,
        type = type,
    )
}
