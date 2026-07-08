package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import kotlinx.coroutines.flow.Flow

interface GetGpgKeyserverConfig : () -> Flow<GpgKeyserverConfig>
