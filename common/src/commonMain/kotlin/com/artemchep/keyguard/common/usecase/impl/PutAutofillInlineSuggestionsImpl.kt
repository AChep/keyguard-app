package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillInlineSuggestionsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(inlineSuggestions: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillInlineSuggestions(inlineSuggestions)
}
