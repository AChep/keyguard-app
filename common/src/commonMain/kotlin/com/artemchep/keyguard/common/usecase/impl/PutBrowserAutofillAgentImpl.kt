package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgent

class PutBrowserAutofillAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserAutofillAgent {
    override fun invoke(browserAutofillAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setBrowserAutofillAgent(browserAutofillAgent)
}
