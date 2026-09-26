package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutWriteAccess

class PutWriteAccessImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutWriteAccess {
    override fun invoke(writeAccess: Boolean): IO<Unit> = settingsReadWriteRepository
        .setWriteAccess(writeAccess)
}
