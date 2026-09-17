package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection

class PutAutofillManualSelectionImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillManualSelection {
    override fun invoke(manualSelection: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillManualSelection(manualSelection)
}
