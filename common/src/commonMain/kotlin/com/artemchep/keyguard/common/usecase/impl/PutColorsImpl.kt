package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutColors

class PutColorsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutColors {
    override fun invoke(colors: AppColors?): IO<Unit> = settingsReadWriteRepository
        .setColors(colors)
}
