package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.VaultState
import kotlinx.coroutines.flow.Flow

interface UnlockUseCase : () -> Flow<VaultState>
