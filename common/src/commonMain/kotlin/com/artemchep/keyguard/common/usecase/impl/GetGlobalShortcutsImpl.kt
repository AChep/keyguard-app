package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGlobalShortcuts

class GetGlobalShortcutsImpl(
    repository: SettingsReadRepository,
) : GetGlobalShortcuts {
    private val flow = repository.getGlobalShortcuts()

    override fun invoke() = flow
}
