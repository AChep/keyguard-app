package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavLabel
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutNavLabelImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavLabel {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(visible: Boolean): IO<Unit> = settingsReadWriteRepository
        .setNavLabel(visible)
}
