package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutLocale

class PutLocaleImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutLocale {
    override fun invoke(locale: String?): IO<Unit> = settingsReadWriteRepository
        .setLocale(locale)
}
