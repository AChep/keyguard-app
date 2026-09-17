package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled

class PutAutofillPasskeysEnabledImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillPasskeysEnabled {
    override fun invoke(advertisePasskeysSupport: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAdvertisePasskeysSupport(advertisePasskeysSupport)
}
