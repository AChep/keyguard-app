package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutCheckTwoFAImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckTwoFA {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(checkTwoFA: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckTwoFA(checkTwoFA)
}
