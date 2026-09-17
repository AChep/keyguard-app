package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape

class PutAllowTwoPanelLayoutInLandscapeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowTwoPanelLayoutInLandscape {
    override fun invoke(allow: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAllowTwoPanelLayoutInLandscape(allow)
}
