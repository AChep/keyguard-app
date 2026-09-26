package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository

class PutAutofillInlineSuggestionsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions {
    override fun invoke(inlineSuggestions: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillInlineSuggestions(inlineSuggestions)
}
