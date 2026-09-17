package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavLabel

class PutNavLabelImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavLabel {
    override fun invoke(visible: Boolean): IO<Unit> = settingsReadWriteRepository
        .setNavLabel(visible)
}
