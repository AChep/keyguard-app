package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutOnboardingLastVisitInstantImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutOnboardingLastVisitInstant {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(instant: Instant): IO<Unit> = settingsReadWriteRepository
        .setOnboardingLastVisitInstant(instant)
}
