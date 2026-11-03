package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryMode
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import kotlinx.coroutines.flow.Flow

interface GetCliAgentUsageHistory : (CliAgentUsageHistoryMode) -> Flow<List<DCliAgentUsageHistory>>
