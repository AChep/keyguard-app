package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutWriteAccessImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutWriteAccess {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(writeAccess: Boolean): IO<Unit> = settingsReadWriteRepository
        .setWriteAccess(writeAccess)
}
