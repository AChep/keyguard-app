package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillRespectAutofillOffImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillRespectAutofillOff {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(respectAutofillOff: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillRespectAutofillOff(respectAutofillOff)
}
