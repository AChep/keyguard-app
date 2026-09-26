package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA

class PutCheckTwoFAImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckTwoFA {
    override fun invoke(checkTwoFA: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckTwoFA(checkTwoFA)
}
