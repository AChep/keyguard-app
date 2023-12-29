package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillManualSelectionImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillManualSelection {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(manualSelection: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAutofillManualSelection(manualSelection)
}
