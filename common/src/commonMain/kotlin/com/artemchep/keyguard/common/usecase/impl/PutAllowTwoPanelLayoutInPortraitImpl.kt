package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAllowTwoPanelLayoutInPortraitImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowTwoPanelLayoutInPortrait {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(allow: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAllowTwoPanelLayoutInPortrait(allow)
}
