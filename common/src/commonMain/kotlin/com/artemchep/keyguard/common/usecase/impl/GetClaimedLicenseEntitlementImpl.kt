package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetClaimedLicenseEntitlement
import kotlinx.coroutines.flow.Flow

class GetClaimedLicenseEntitlementImpl(
    private val licenseManager: LicenseManager,
) : GetClaimedLicenseEntitlement {
    override fun invoke(
    ): Flow<LicenseEntitlement?> = licenseManager.claimed
}
