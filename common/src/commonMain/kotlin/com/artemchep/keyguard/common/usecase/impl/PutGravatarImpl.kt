package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGravatar
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGravatarImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGravatar {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setGravatar(enabled)
}
