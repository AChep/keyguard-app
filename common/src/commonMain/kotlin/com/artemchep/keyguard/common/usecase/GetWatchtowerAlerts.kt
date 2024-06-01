package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DWatchtowerAlert
import kotlinx.coroutines.flow.Flow

interface GetWatchtowerAlerts : () -> Flow<List<DWatchtowerAlert>>
