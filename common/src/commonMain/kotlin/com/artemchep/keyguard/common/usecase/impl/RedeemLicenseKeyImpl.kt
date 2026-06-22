package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.usecase.RedeemLicenseKey
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RedeemLicenseKeyImpl(
    private val licenseManager: LicenseManager,
) : RedeemLicenseKey {
    constructor(directDI: DirectDI) : this(
        licenseManager = directDI.instance(),
    )

    override fun invoke(
        licenseKey: String,
    ): IO<LicenseEntitlement> = licenseManager
        .redeem(licenseKey)
}
