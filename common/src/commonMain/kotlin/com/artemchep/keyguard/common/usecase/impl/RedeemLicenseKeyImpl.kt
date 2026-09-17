package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.RedeemLicenseKey

class RedeemLicenseKeyImpl(
    private val licenseManager: LicenseManager,
) : RedeemLicenseKey {
    override fun invoke(
        licenseKey: String,
    ): IO<LicenseEntitlement> = licenseManager
        .redeem(licenseKey)
}
