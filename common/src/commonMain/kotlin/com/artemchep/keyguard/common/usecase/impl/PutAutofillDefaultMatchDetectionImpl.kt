package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAutofillDefaultMatchDetectionImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAutofillDefaultMatchDetection {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(
        matchDetection: DSecret.Uri.MatchType,
    ): IO<Unit> = settingsReadWriteRepository
        .setAutofillDefaultMatchDetection(matchDetection.name)
}
