package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillPasskeysEnabledImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillPasskeysEnabled {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(advertisePasskeysSupport: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAdvertisePasskeysSupport(advertisePasskeysSupport)
}
