package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillCopyTotpImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillCopyTotp {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(copyTotp: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillCopyTotp(copyTotp)
}
