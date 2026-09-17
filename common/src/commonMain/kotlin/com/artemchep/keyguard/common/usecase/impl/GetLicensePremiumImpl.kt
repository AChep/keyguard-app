package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.util.flowOfTime
import kotlin.time.DurationUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

    override fun invoke(): Flow<Boolean> = premiumFlow
}
