package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgent
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutBrowserAutofillAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserAutofillAgent {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(browserAutofillAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setBrowserAutofillAgent(browserAutofillAgent)
}
