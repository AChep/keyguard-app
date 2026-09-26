package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff

class PutAutofillRespectAutofillOffImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillRespectAutofillOff {
    override fun invoke(respectAutofillOff: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillRespectAutofillOff(respectAutofillOff)
}
