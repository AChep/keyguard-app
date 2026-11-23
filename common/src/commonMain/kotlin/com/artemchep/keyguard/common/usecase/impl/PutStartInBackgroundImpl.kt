package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutStartInBackground

class PutStartInBackgroundImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutStartInBackground {
    override fun invoke(startInBackground: Boolean): IO<Unit> = settingsReadWriteRepository
        .setStartInBackground(startInBackground)
}
