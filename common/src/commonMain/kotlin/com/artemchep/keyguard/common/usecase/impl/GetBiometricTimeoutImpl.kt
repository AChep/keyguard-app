package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class GetBiometricTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBiometricTimeout {
    companion object {
        private val DEFAULT_DURATION = with(Duration) { 14L.days }
    }

    private val sharedFlow = settingsReadRepository.getBiometricTimeout()
        .map { duration ->
            duration ?: DEFAULT_DURATION
        }
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Duration> = sharedFlow
}
