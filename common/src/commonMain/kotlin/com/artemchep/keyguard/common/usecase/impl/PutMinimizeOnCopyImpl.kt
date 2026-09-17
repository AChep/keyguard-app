package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy

class PutMinimizeOnCopyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutMinimizeOnCopy {
    override fun invoke(minimizeOnCopy: Boolean): IO<Unit> = settingsReadWriteRepository
        .setMinimizeOnCopy(minimizeOnCopy)
}
