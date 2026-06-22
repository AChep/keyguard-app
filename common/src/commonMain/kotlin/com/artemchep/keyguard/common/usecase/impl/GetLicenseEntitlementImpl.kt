package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetLicenseEntitlementImpl(
    private val licenseManager: LicenseManager,
) : GetLicenseEntitlement {
    constructor(directDI: DirectDI) : this(
        licenseManager = directDI.instance(),
    )

    override fun invoke(
    ): Flow<LicenseEntitlement?> = licenseManager.redeemed
}
