package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.usecase.RemoveLicense

class RemoveLicenseImpl(
    private val licenseManager: LicenseManager,
) : RemoveLicense {
    override fun invoke(): IO<Unit> = licenseManager.clearRedeemed()
}
