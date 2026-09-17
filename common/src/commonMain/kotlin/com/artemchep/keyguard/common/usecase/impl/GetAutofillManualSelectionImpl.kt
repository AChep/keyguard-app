package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillManualSelectionImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillManualSelection {
    private val sharedFlow = settingsReadRepository.getAutofillManualSelection()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
