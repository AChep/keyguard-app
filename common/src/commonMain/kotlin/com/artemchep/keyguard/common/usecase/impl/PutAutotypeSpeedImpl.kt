package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AutotypeSpeed
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutotypeSpeed

class PutAutotypeSpeedImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutotypeSpeed {
    override fun invoke(speed: AutotypeSpeed): IO<Unit> = settingsReadWriteRepository
        .setAutotypeSpeed(speed)
}
