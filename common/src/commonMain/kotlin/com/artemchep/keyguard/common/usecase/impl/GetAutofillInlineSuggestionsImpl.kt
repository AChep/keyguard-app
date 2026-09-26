package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillInlineSuggestionsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillInlineSuggestions {
    private val sharedFlow = settingsReadRepository.getAutofillInlineSuggestions()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
