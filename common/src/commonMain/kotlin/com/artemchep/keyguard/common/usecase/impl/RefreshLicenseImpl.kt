package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.RefreshLicense

class RefreshLicenseImpl(
    private val licenseManager: LicenseManager,
) : RefreshLicense {
    override fun invoke(
    ): IO<LicenseEntitlement?> = licenseManager
        .refreshRedeemedIfNeeded()
}
