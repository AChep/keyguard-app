package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.GpgUsageHistoryMode
import kotlinx.coroutines.flow.Flow

interface GetGpgUsageHistory : (GpgUsageHistoryMode) -> Flow<List<DGpgUsageHistory>>
