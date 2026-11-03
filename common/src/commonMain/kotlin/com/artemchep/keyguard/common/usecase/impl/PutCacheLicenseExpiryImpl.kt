package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCacheLicenseExpiry

class PutCacheLicenseExpiryImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCacheLicenseExpiry {
    override fun invoke(expiry: LicenseEntitlement.Expiry?): IO<Unit> = settingsReadWriteRepository
        .setCacheLicenseExpiry(expiry)
}
