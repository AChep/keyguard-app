package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutColors
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutColorsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutColors {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(colors: AppColors?): IO<Unit> = settingsReadWriteRepository
        .setColors(colors)
}
