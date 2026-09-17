package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavAnimation

class PutNavAnimationImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavAnimation {
    override fun invoke(navAnimation: NavAnimation?): IO<Unit> = settingsReadWriteRepository
        .setNavAnimation(navAnimation)
}
