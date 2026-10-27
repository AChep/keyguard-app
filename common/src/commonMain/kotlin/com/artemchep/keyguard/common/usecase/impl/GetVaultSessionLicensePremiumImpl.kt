package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.VaultLicenseSessionAccess
import com.artemchep.keyguard.common.usecase.GetCacheLicenseExpiry
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

class GetVaultSessionLicensePremiumImpl(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: VaultLicenseSessionAccess,
    getCacheLicenseExpiry: GetCacheLicenseExpiry,
) : GetLicensePremium {
    private val cachedPremiumFlow = getCacheLicenseExpiry()
        .isCurrentlyLicensedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun invoke(): Flow<Boolean> = getVaultSession()
        .flatMapLatest { session ->
            val key = session as? MasterSession.Key
            key?.let(sessionAccess::invoke)?.invoke()
                // The vault is locked, so use the license
                // cached during the last unlock.
                ?: cachedPremiumFlow
        }
        .distinctUntilChanged()
}
