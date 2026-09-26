package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import kotlinx.coroutines.flow.Flow

class GetLicenseEntitlementImpl(
    private val licenseManager: LicenseManager,
) : GetLicenseEntitlement {
    override fun invoke(
    ): Flow<LicenseEntitlement?> = licenseManager.redeemed
}
