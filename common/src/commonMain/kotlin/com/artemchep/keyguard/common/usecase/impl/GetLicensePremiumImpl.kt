package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.util.flowOfTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.DurationUnit

class GetLicensePremiumImpl(
    private val getLicenseEntitlement: GetLicenseEntitlement,
) : GetLicensePremium {
    private val premiumFlow = combine(
        flowOfTime(
            unit = DurationUnit.HOURS,
            duration = 1L,
        ),
        getLicenseEntitlement(),
    ) { now, entitlement ->
        entitlement?.isCurrentlyLicensed(now = now)
            ?: false
    }

    constructor(directDI: DirectDI) : this(
        getLicenseEntitlement = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = premiumFlow
}
