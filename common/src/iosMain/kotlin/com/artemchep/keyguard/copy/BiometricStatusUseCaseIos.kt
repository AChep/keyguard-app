package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object BiometricStatusUseCaseIos : BiometricStatusUseCase {
    override fun invoke(): Flow<BiometricStatus> = flowOf(BiometricStatus.Unavailable)
}
