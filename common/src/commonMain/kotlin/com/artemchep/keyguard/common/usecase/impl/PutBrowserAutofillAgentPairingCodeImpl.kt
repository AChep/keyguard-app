package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserAutofillAgentPairingCode

class PutBrowserAutofillAgentPairingCodeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserAutofillAgentPairingCode {
    override fun invoke(code: String): IO<Unit> = settingsReadWriteRepository
        .setBrowserAutofillAgentPairingCode(code)
}
