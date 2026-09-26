package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import kotlin.time.Instant

class PutOnboardingLastVisitInstantImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutOnboardingLastVisitInstant {
    override fun invoke(instant: Instant): IO<Unit> = settingsReadWriteRepository
        .setOnboardingLastVisitInstant(instant)
}
