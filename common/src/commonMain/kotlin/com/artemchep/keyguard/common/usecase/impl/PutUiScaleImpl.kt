package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutUiScale

class PutUiScaleImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutUiScale {
    override fun invoke(uiScale: Float): IO<Unit> = settingsReadWriteRepository
        .setUiScale(uiScale)
}
