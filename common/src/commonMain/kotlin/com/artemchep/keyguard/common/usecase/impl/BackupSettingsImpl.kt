package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.BackupSettings

class BackupSettingsImpl(
    private val settingsReadRepository: SettingsReadRepository,
) : BackupSettings {
    override fun invoke(): IO<Unit> = settingsReadRepository
        .backup()
        .effectMap {
            // For now we only print the contents of the
            // backup for testing purposes.
            println(it)
        }
}
