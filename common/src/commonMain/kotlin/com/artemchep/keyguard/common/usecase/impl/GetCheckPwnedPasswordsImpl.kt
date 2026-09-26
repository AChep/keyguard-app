package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCheckPwnedPasswordsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckPwnedPasswords {
    private val sharedFlow = settingsReadRepository.getCheckPwnedPasswords()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
