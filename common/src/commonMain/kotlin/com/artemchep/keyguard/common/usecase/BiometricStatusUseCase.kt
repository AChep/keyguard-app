package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.BiometricStatus
import kotlinx.coroutines.flow.Flow

interface BiometricStatusUseCase : () -> Flow<BiometricStatus>
