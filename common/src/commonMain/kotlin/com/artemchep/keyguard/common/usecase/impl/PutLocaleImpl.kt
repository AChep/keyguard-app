package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutLocale
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutLocaleImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutLocale {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(locale: String?): IO<Unit> = settingsReadWriteRepository
        .setLocale(locale)
}
