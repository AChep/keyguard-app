package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait

class PutAllowTwoPanelLayoutInPortraitImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowTwoPanelLayoutInPortrait {
    override fun invoke(allow: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAllowTwoPanelLayoutInPortrait(allow)
}
