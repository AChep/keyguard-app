package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.usecase.RemoveLicense
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveLicenseImpl(
    private val licenseManager: LicenseManager,
) : RemoveLicense {
    constructor(directDI: DirectDI) : this(
        licenseManager = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = licenseManager.clearRedeemed()
}
