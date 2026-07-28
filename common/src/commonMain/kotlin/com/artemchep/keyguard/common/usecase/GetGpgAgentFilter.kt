package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.GpgAgentFilter
import kotlinx.coroutines.flow.Flow

interface GetGpgAgentFilter : () -> Flow<GpgAgentFilter>
