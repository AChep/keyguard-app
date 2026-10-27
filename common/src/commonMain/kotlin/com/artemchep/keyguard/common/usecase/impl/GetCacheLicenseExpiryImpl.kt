package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCacheLicenseExpiry
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCacheLicenseExpiryImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCacheLicenseExpiry {
    private val sharedFlow = settingsReadRepository
        .getCacheLicenseExpiry()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
