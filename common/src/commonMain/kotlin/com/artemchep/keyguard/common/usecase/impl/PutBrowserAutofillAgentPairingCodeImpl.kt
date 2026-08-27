package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgentPairingCode
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutBrowserAutofillAgentPairingCodeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserAutofillAgentPairingCode {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(code: String): IO<Unit> = settingsReadWriteRepository
        .setBrowserAutofillAgentPairingCode(code)
}
