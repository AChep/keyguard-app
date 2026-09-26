package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGravatarImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGravatar {
    private val sharedFlow = settingsReadRepository.getGravatar()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
