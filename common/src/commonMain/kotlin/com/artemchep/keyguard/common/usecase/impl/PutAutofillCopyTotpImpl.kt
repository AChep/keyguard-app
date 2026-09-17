package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp

class PutAutofillCopyTotpImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillCopyTotp {
    override fun invoke(copyTotp: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillCopyTotp(copyTotp)
}
