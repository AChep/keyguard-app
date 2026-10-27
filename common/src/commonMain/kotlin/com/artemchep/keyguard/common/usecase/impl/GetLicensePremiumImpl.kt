package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.service.licensekey.model.licensedUntil
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.util.flowOfTime
import kotlin.time.DurationUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class GetLicensePremiumImpl(
    private val getLicenseEntitlement: GetLicenseEntitlement,
) : GetLicensePremium {
    private val premiumFlow = getLicenseEntitlement()
        .map { entitlement -> entitlement?.licensedUntil() }
        .isCurrentlyLicensedFlow()

    override fun invoke(): Flow<Boolean> = premiumFlow
}

/**
 * Re-checks the expiry every hour, so premium
 * ends with the paid-through month.
 */
internal fun Flow<LicenseEntitlement.Expiry?>.isCurrentlyLicensedFlow(): Flow<Boolean> = combine(
    flowOfTime(
        unit = DurationUnit.HOURS,
        duration = 1L,
    ),
    this,
) { now, expiry ->
    expiry?.isCurrentlyLicensed(now = now)
        ?: false
}
