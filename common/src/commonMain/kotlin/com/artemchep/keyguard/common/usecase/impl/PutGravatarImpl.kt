package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGravatar

class PutGravatarImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGravatar {
    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setGravatar(enabled)
}
