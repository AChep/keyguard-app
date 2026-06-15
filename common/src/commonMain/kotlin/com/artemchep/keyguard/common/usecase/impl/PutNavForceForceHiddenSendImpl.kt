package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavForceHiddenSend
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutNavForceForceHiddenSendImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavForceHiddenSend {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(visible: Boolean): IO<Unit> = settingsReadWriteRepository
        .setNavHiddenSend(visible)
}
