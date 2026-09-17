package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.VaultLicenseSessionAccess
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class GetVaultSessionLicensePremiumImpl(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: VaultLicenseSessionAccess,
) : GetLicensePremium {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun invoke(): Flow<Boolean> = getVaultSession()
        .flatMapLatest { session ->
            val key = session as? MasterSession.Key
                ?: return@flatMapLatest flowOf(false)
            sessionAccess(key)?.invoke() ?: flowOf(false)
        }
        .distinctUntilChanged()
}
