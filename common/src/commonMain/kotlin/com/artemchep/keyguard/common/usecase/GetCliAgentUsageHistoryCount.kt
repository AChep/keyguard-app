package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import kotlinx.coroutines.flow.Flow

interface GetCliAgentUsageHistoryCount : (CliAgentUsageHistoryClient) -> Flow<Long>
