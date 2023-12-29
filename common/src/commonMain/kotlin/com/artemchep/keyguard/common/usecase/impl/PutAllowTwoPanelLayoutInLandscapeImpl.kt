package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAllowTwoPanelLayoutInLandscapeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowTwoPanelLayoutInLandscape {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(allow: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAllowTwoPanelLayoutInLandscape(allow)
}
