package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

class GetVaultSessionLicensePremiumImpl(
    private val getVaultSession: GetVaultSession,
) : GetLicensePremium {
    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun invoke(): Flow<Boolean> = getVaultSession()
        .flatMapLatest { session ->
            val key = session as? MasterSession.Key
                ?: return@flatMapLatest flowOf(false)
            key.di.direct
                .instance<GetLicensePremium>()
                .invoke()
        }
        .distinctUntilChanged()
}
