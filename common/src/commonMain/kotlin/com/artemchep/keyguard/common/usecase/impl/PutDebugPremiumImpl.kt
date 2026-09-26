package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutDebugPremium

class PutDebugPremiumImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutDebugPremium {
    override fun invoke(premium: Boolean): IO<Unit> = settingsReadWriteRepository
        .setDebugPremium(premium)
}
