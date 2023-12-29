package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutFont
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutFontImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutFont {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(font: AppFont?): IO<Unit> = settingsReadWriteRepository
        .setFont(font)
}
