package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutMinimizeOnCopyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutMinimizeOnCopy {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(minimizeOnCopy: Boolean): IO<Unit> = settingsReadWriteRepository
        .setMinimizeOnCopy(minimizeOnCopy)
}
