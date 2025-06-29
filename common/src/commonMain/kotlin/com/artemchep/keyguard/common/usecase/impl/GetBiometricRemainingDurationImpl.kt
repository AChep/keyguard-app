package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.vault.SessionMetadataReadRepository
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.util.flowOfTime
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration
import kotlin.time.DurationUnit

class GetBiometricRemainingDurationImpl(
    private val sessionMetadataReadRepository: SessionMetadataReadRepository,
    private val getBiometricTimeout: GetBiometricTimeout,
) : GetBiometricRemainingDuration {
    constructor(directDI: DirectDI) : this(
        sessionMetadataReadRepository = directDI.instance(),
        getBiometricTimeout = directDI.instance(),
    )

    override fun invoke(): Flow<Duration> = combine(
        getExpiryTimeFlow(),
        flowOfTime(DurationUnit.MINUTES),
    ) { expiryTimeOrNull, currentTime ->
        val expiryTime = expiryTimeOrNull ?: return@combine Duration.ZERO
        (expiryTime - currentTime)
            .coerceAtLeast(Duration.ZERO)
    }
        .catch { e ->
            recordException(e)
            // Disable biometric if there's an error calculating
            // the duration.
            emit(Duration.ZERO)
        }

    private fun getExpiryTimeFlow() = combine(
        sessionMetadataReadRepository.getLastPasswordUseTimestamp(),
        getBiometricTimeout(),
    ) { lastPasswordUseInstant, timeout ->
        when (timeout) {
            Duration.INFINITE -> return@combine Instant.DISTANT_FUTURE
            Duration.ZERO -> return@combine Instant.DISTANT_PAST
        }

        lastPasswordUseInstant?.plus(timeout)
    }
}
