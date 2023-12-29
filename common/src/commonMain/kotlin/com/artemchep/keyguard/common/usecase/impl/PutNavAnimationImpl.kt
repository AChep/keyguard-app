package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutNavAnimationImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavAnimation {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(navAnimation: NavAnimation?): IO<Unit> = settingsReadWriteRepository
        .setNavAnimation(navAnimation)
}
