package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCheckPwnedPasswordsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckPwnedPasswords {
    private val sharedFlow = settingsReadRepository.getCheckPwnedPasswords()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
