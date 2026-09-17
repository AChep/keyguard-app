package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GetVaultLockAfterTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetVaultLockAfterTimeout {
    companion object {
        private val DEFAULT_DURATION = with(Duration) { 5L.minutes }
    }

    private val sharedFlow = settingsReadRepository.getVaultTimeout()
        .map { duration ->
            duration ?: DEFAULT_DURATION
        }
        .distinctUntilChanged()

    override fun invoke(): Flow<Duration> = sharedFlow
}
