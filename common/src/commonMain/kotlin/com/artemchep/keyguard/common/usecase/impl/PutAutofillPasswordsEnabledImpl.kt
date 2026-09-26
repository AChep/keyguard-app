package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.PutAutofillPasswordsEnabled

class PutAutofillPasswordsEnabledImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillPasswordsEnabled {
    override fun invoke(advertisePasswordsSupport: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAdvertisePasswordsSupport(advertisePasswordsSupport)
}
