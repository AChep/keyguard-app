package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillInlineSuggestionsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillInlineSuggestions {
    private val sharedFlow = settingsReadRepository.getAutofillInlineSuggestions()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
