package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCachePremium

class PutCachePremiumImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCachePremium {
    override fun invoke(premium: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCachePremium(premium)
}
